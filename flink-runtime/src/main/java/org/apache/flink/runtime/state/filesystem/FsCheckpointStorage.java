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

package org.apache.flink.runtime.state.filesystem;

import org.apache.flink.api.common.JobID;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.state.CheckpointStorageLocation;
import org.apache.flink.runtime.state.CheckpointStorageLocationReference;
import org.apache.flink.runtime.state.CheckpointStreamFactory;
import org.apache.flink.runtime.state.CheckpointStreamFactory.CheckpointStateOutputStream;
import org.apache.flink.runtime.state.filesystem.FsCheckpointStreamFactory.FsCheckpointStateOutputStream;
import org.apache.flink.util.Preconditions;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

import static org.apache.flink.util.Preconditions.checkArgument;

/**
 * An implementation of durable checkpoint storage to file systems.
 */
public class FsCheckpointStorage extends AbstractFsCheckpointStorage {

	private final FileSystem fileSystem;

	private final Path checkpointsDirectory;

	private final Path exclusiveStateDirectory;

	private final Path sharedStateDirectory;

	private final Path taskOwnedStateDirectory;

	private final int fileSizeThreshold;

	/** The number of sub-directories for exclusive/shared/taskOwned state directory. */
	private int stateSubDirs;

	public FsCheckpointStorage(
			Path checkpointBaseDirectory,
			@Nullable Path defaultSavepointDirectory,
			JobID jobId,
			boolean createCheckpointSubDir,
			int fileSizeThreshold) throws IOException {

		super(jobId, defaultSavepointDirectory);

		checkArgument(fileSizeThreshold >= 0);

		this.fileSystem = checkpointBaseDirectory.getFileSystem();
		this.checkpointsDirectory = createCheckpointSubDir ?
			getCheckpointDirectoryForJob(checkpointBaseDirectory, jobId) : checkpointBaseDirectory;
		this.exclusiveStateDirectory = new Path(checkpointsDirectory, CHECKPOINT_EXCLUSICE_STATE_DIR);
		this.sharedStateDirectory = new Path(checkpointsDirectory, CHECKPOINT_SHARED_STATE_DIR);
		this.taskOwnedStateDirectory = new Path(checkpointsDirectory, CHECKPOINT_TASK_OWNED_STATE_DIR);
		this.fileSizeThreshold = fileSizeThreshold;
		this.stateSubDirs = 0;

		// initialize the dedicated directories
		fileSystem.mkdirs(checkpointsDirectory);
		fileSystem.mkdirs(exclusiveStateDirectory);
		fileSystem.mkdirs(sharedStateDirectory);
		fileSystem.mkdirs(taskOwnedStateDirectory);
	}

	// ------------------------------------------------------------------------

	public Path getCheckpointsDirectory() {
		return checkpointsDirectory;
	}

	public void setStateSubDirs(int stateSubDirs) {
		Preconditions.checkArgument(stateSubDirs >= 0, "assigned state sub-directories number cannot be less than zero.");
		this.stateSubDirs = stateSubDirs;
	}

	public int getStateSubDirs() {
		return stateSubDirs;
	}

	// ------------------------------------------------------------------------
	//  CheckpointStorage implementation
	// ------------------------------------------------------------------------

	@Override
	public boolean supportsHighlyAvailableStorage() {
		return true;
	}

	@Override
	public CheckpointStorageLocation initializeLocationForCheckpoint(long checkpointId) throws IOException {
		checkArgument(checkpointId >= 0);

		// prepare all the paths needed for the checkpoints
		final Path checkpointDir = createCheckpointDirectory(checkpointsDirectory, checkpointId);

		// create the checkpoint exclusive directory
		fileSystem.mkdirs(checkpointDir);

		return new FsCheckpointStorageLocation(
				fileSystem,
				checkpointDir,
				exclusiveStateDirectory,
				sharedStateDirectory,
				taskOwnedStateDirectory,
				CheckpointStorageLocationReference.getDefault(),
				fileSizeThreshold);
	}

	@Override
	public CheckpointStreamFactory resolveCheckpointStorageLocation(
			long checkpointId,
			CheckpointStorageLocationReference reference) throws IOException {

		if (reference.isDefaultReference()) {
			// default reference, construct the default location for that particular checkpoint
			final Path checkpointDir = createCheckpointDirectory(checkpointsDirectory, checkpointId);

			if (stateSubDirs > 0) {
				return new FsCheckpointStorageLocation(
					fileSystem,
					checkpointDir,
					new Path(exclusiveStateDirectory, String.valueOf(ThreadLocalRandom.current().nextInt(stateSubDirs))),
					new Path(sharedStateDirectory, String.valueOf(ThreadLocalRandom.current().nextInt(stateSubDirs))),
					new Path(taskOwnedStateDirectory, String.valueOf(ThreadLocalRandom.current().nextInt(stateSubDirs))),
					reference,
					fileSizeThreshold);
			} else {
				return new FsCheckpointStorageLocation(
					fileSystem,
					checkpointDir,
					exclusiveStateDirectory,
					sharedStateDirectory,
					taskOwnedStateDirectory,
					reference,
					fileSizeThreshold);
			}
		}
		else {
			// location encoded in the reference
			final Path path = decodePathFromReference(reference);

			return new FsCheckpointStorageLocation(
					path.getFileSystem(),
					path,
					path,
					path,
					path,
					reference,
					fileSizeThreshold);
		}
	}

	@Override
	public CheckpointStateOutputStream createTaskOwnedStateStream() throws IOException {
		return new FsCheckpointStateOutputStream(
				taskOwnedStateDirectory,
				fileSystem,
				DUMMY_CHECKPOINT_ID,
				FsCheckpointStreamFactory.DEFAULT_WRITE_BUFFER_SIZE,
				fileSizeThreshold);
	}

	@Override
	protected CheckpointStorageLocation createSavepointLocation(FileSystem fs, Path location) throws IOException {
		final CheckpointStorageLocationReference reference = encodePathAsReference(location);
		return new FsCheckpointStorageLocation(fs, location, location, location, location, reference, fileSizeThreshold);
	}
}
