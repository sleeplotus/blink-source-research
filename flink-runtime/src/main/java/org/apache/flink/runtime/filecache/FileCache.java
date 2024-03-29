/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.filecache;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.cache.DistributedCache.DistributedCacheEntry;
import org.apache.flink.core.fs.FSDataInputStream;
import org.apache.flink.core.fs.FSDataOutputStream;
import org.apache.flink.core.fs.FileStatus;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.blob.PermanentBlobKey;
import org.apache.flink.runtime.blob.PermanentBlobService;
import org.apache.flink.runtime.executiongraph.ExecutionAttemptID;
import org.apache.flink.runtime.util.ExecutorThreadFactory;
import org.apache.flink.util.FileUtils;
import org.apache.flink.util.IOUtils;
import org.apache.flink.util.InstantiationUtil;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.ShutdownHookUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * The FileCache is used to create the local files for the registered cache files when a task is deployed.
 * The files will be removed when the task is unregistered after a 5 second delay.
 * A given file x will be placed in "{@code <system-tmp-dir>/tmp_<jobID>/}".
 */
public class FileCache {

	private static final Logger LOG = LoggerFactory.getLogger(FileCache.class);

	/** cache-wide lock to ensure consistency. copies are not done under this lock. */
	private final Object lock = new Object();

	private final Map<JobID, Map<String, Future<Path>>> entries;

	private final Map<JobID, Set<ExecutionAttemptID>> jobRefHolders;

	private final ScheduledExecutorService executorService;

	private final File[] storageDirectories;

	private final Thread shutdownHook;

	private int nextDirectory;

	private final PermanentBlobService blobService;

	private final long cleanupInterval; //in milliseconds

	// ------------------------------------------------------------------------

	public FileCache(String[] tempDirectories, PermanentBlobService blobService) throws IOException {
		this (tempDirectories, blobService, Executors.newScheduledThreadPool(10,
			new ExecutorThreadFactory("flink-file-cache")), 5000);
	}

	@VisibleForTesting
	FileCache(String[] tempDirectories, PermanentBlobService blobService,
		ScheduledExecutorService executorService, long cleanupInterval) throws IOException {

		Preconditions.checkNotNull(tempDirectories);
		this.cleanupInterval = cleanupInterval;

		storageDirectories = new File[tempDirectories.length];

		for (int i = 0; i < tempDirectories.length; i++) {
			String cacheDirName = "flink-dist-cache-" + UUID.randomUUID().toString();
			storageDirectories[i] = new File(tempDirectories[i], cacheDirName);
			String path = storageDirectories[i].getAbsolutePath();

			if (storageDirectories[i].mkdirs()) {
				LOG.info("User file cache uses directory " + path);
			} else {
				LOG.error("User file cache cannot create directory " + path);
				// delete all other directories we created so far
				for (int k = 0; k < i; k++) {
					if (!storageDirectories[k].delete()) {
						LOG.warn("User file cache cannot remove prior directory " +
								storageDirectories[k].getAbsolutePath());
					}
				}
				throw new IOException("File cache cannot create temp storage directory: " + path);
			}
		}

		this.shutdownHook = createShutdownHook(this, LOG);

		this.entries = new HashMap<>();
		this.jobRefHolders = new HashMap<>();
		this.executorService = executorService;
		this.blobService = blobService;
	}

	/**
	 * Shuts down the file cache by cancelling all.
	 */
	public void shutdown() {
		synchronized (lock) {
			// first shutdown the thread pool
			ScheduledExecutorService es = this.executorService;
			if (es != null) {
				es.shutdown();
				try {
					es.awaitTermination(cleanupInterval, TimeUnit.MILLISECONDS);
				}
				catch (InterruptedException e) {
					// may happen
				}
			}

			entries.clear();
			jobRefHolders.clear();

			// clean up the all storage directories
			for (File dir : storageDirectories) {
				try {
					FileUtils.deleteDirectory(dir);
				}
				catch (IOException e) {
					LOG.error("File cache could not properly clean up storage directory.");
				}
			}

			// Remove shutdown hook to prevent resource leaks
			ShutdownHookUtil.removeShutdownHook(shutdownHook, getClass().getSimpleName(), LOG);
		}
	}

	// ------------------------------------------------------------------------

	/**
	 * If the file doesn't exists locally, it will copy the file to the temp directory.
	 *
	 * @param entry The cache entry descriptor (path, executable flag)
	 * @param jobID The ID of the job for which the file is copied.
	 * @return The handle to the task that copies the file.
	 */
	public Future<Path> createTmpFile(String name, DistributedCacheEntry entry, JobID jobID, ExecutionAttemptID executionId) throws Exception {
		synchronized (lock) {
			Map<String, Future<Path>> jobEntries = entries.computeIfAbsent(jobID, k -> new HashMap<>());

			// register reference holder
			final Set<ExecutionAttemptID> refHolders = jobRefHolders.computeIfAbsent(jobID, id -> new HashSet<>());
			refHolders.add(executionId);

			Future<Path> fileEntry = jobEntries.get(name);
			if (fileEntry != null) {
				// file is already in the cache. return a future that
				// immediately returns the file
				return fileEntry;
			} else {
				// need to copy the file

				// create the target path
				File tempDirToUse = new File(storageDirectories[nextDirectory++], jobID.toString());
				if (nextDirectory >= storageDirectories.length) {
					nextDirectory = 0;
				}

				// kick off the copying
				Callable<Path> cp;
				if (entry.blobKey != null) {
					cp = new CopyFromBlobProcess(entry, jobID, blobService, new Path(tempDirToUse.getAbsolutePath()));
				} else {
					cp = new CopyFromDFSProcess(entry, new Path(tempDirToUse.getAbsolutePath()));
				}
				FutureTask<Path> copyTask = new FutureTask<>(cp);
				executorService.submit(copyTask);

				// store our entry
				jobEntries.put(name, copyTask);

				return copyTask;
			}
		}
	}

	// ------------------------------------------------------------------------
	//  Utilities
	// ------------------------------------------------------------------------

	public static void copy(Path sourcePath, Path targetPath, boolean executable) throws IOException {
		// TODO rewrite this to make it participate in the closable registry and the lifecycle of a task.
		// we unwrap the file system to get raw streams without safety net
		FileSystem sFS = FileSystem.getUnguardedFileSystem(sourcePath.toUri());
		FileSystem tFS = FileSystem.getUnguardedFileSystem(targetPath.toUri());
		if (!tFS.exists(targetPath)) {
			if (sFS.getFileStatus(sourcePath).isDir()) {
				tFS.mkdirs(targetPath);
				FileStatus[] contents = sFS.listStatus(sourcePath);
				for (FileStatus content : contents) {
					String distPath = content.getPath().toString();
					if (content.isDir()) {
						if (distPath.endsWith("/")) {
							distPath = distPath.substring(0, distPath.length() - 1);
						}
					}
					String localPath = targetPath.toString() + distPath.substring(distPath.lastIndexOf("/"));
					copy(content.getPath(), new Path(localPath), executable);
				}
			} else {
				try (FSDataOutputStream lfsOutput = tFS.create(targetPath, FileSystem.WriteMode.NO_OVERWRITE); FSDataInputStream fsInput = sFS.open(sourcePath)) {
					IOUtils.copyBytes(fsInput, lfsOutput);
					//noinspection ResultOfMethodCallIgnored
					new File(targetPath.toString()).setExecutable(executable);
				} catch (IOException ioe) {
					LOG.error("could not copy file to local file cache.", ioe);
				}
			}
		}
	}

	private static Thread createShutdownHook(final FileCache cache, final Logger logger) {

		return ShutdownHookUtil.addShutdownHook(
			cache::shutdown,
			FileCache.class.getSimpleName(),
			logger
		);
	}

	public void releaseJob(JobID jobId, ExecutionAttemptID executionId) {
		checkNotNull(jobId);

		synchronized (lock) {
			Set<ExecutionAttemptID> jobRefCounter = jobRefHolders.get(jobId);

			if (jobRefCounter == null || jobRefCounter.isEmpty()) {
				LOG.warn("improper use of releaseJob() without a matching number of createTmpFiles() calls for jobId " + jobId);
				return;
			}

			jobRefCounter.remove(executionId);
			if (jobRefCounter.isEmpty()) {
				executorService.schedule(new DeleteProcess(jobId), cleanupInterval, TimeUnit.MILLISECONDS);
			}
		}
	}

	// ------------------------------------------------------------------------
	//  background processes
	// ------------------------------------------------------------------------

	/**
	 * Asynchronous file copy process from blob server.
	 */
	private static class CopyFromBlobProcess implements Callable<Path> {

		private final PermanentBlobKey blobKey;
		private final Path target;
		private final boolean isDirectory;
		private final boolean isExecutable;
		private final JobID jobID;
		private final PermanentBlobService blobService;

		CopyFromBlobProcess(DistributedCacheEntry e, JobID jobID, PermanentBlobService blobService, Path target) throws Exception {
				this.isExecutable = e.isExecutable;
				this.isDirectory = e.isZipped;
				this.jobID = jobID;
				this.blobService = blobService;
				this.blobKey = InstantiationUtil.deserializeObject(e.blobKey, Thread.currentThread().getContextClassLoader());
				this.target = target;
		}

		@Override
		public Path call() throws IOException {
			final File file = blobService.getFile(jobID, blobKey);

			if (isDirectory) {
				try (ZipInputStream zis = new ZipInputStream(new FileInputStream(file))) {
					ZipEntry entry;
					while ((entry = zis.getNextEntry()) != null) {
						String fileName = entry.getName();
						Path newFile = new Path(target, fileName);
						if (entry.isDirectory()) {
							target.getFileSystem().mkdirs(newFile);
						} else {
							try (FSDataOutputStream fsDataOutputStream = target.getFileSystem()
									.create(newFile, FileSystem.WriteMode.NO_OVERWRITE)) {
								IOUtils.copyBytes(zis, fsDataOutputStream, false);
							}
							//noinspection ResultOfMethodCallIgnored
							new File(newFile.getPath()).setExecutable(isExecutable);
						}
						zis.closeEntry();
					}
				}
				Files.delete(file.toPath());
				return target;
			} else {
				//noinspection ResultOfMethodCallIgnored
				file.setExecutable(isExecutable);
				return Path.fromLocalFile(file);
			}

		}
	}

	/**
	 * Asynchronous file copy process.
	 */
	private static class CopyFromDFSProcess implements Callable<Path> {

		private final Path filePath;
		private final Path cachedPath;
		private boolean executable;

		public CopyFromDFSProcess(DistributedCacheEntry e, Path cachedPath) {
			this.filePath = new Path(e.filePath);
			this.executable = e.isExecutable;

			String sourceFile = e.filePath;
			int posOfSep = sourceFile.lastIndexOf("/");
			if (posOfSep > 0) {
				sourceFile = sourceFile.substring(posOfSep + 1);
			}

			this.cachedPath = new Path(cachedPath, sourceFile);

		}

		@Override
		public Path call() throws IOException {
			// let exceptions propagate. we can retrieve them later from
			// the future and report them upon access to the result
			FileUtils.copy(filePath, cachedPath, this.executable);
			return cachedPath;
		}
	}

	/**
	 * If no task is using this file after 5 seconds, clear it.
	 */
	@VisibleForTesting
	class DeleteProcess implements Runnable {

		private final JobID jobID;

		DeleteProcess(JobID jobID) {
			this.jobID = jobID;
		}

		@Override
		public void run() {
			try {
				synchronized (lock) {

					Set<ExecutionAttemptID> jobRefs = jobRefHolders.get(jobID);
					if (jobRefs != null && jobRefs.isEmpty()) {
						// abort the copy
						for (Future<Path> fileFuture : entries.get(jobID).values()) {
							fileFuture.cancel(true);
						}

						//remove job specific entries in maps
						entries.remove(jobID);
						jobRefHolders.remove(jobID);

						// remove the job wide temp directories
						for (File storageDirectory : storageDirectories) {
							File tempDir = new File(storageDirectory, jobID.toString());
							FileUtils.deleteDirectory(tempDir);
						}
					}
				}
			} catch (IOException e) {
				LOG.error("Could not delete file from local file cache.", e);
			}
		}
	}
}
