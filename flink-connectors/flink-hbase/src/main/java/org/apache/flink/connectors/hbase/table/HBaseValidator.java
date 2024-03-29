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

package org.apache.flink.connectors.hbase.table;

import org.apache.flink.annotation.Internal;
import org.apache.flink.table.descriptors.ConnectorDescriptorValidator;
import org.apache.flink.table.descriptors.DescriptorProperties;

import java.util.Arrays;
import java.util.List;

/**
 * The validator for HBase.
 * More features to be supported, e.g., batch read/write, async api(support from hbase version 2.0.0), Caching for LookupFunction.
 */
@Internal
public class HBaseValidator extends ConnectorDescriptorValidator {
	public static final String DEFAULT_ROW_KEY = "ROWKEY";
	public static final String COLUMNFAMILY_QUALIFIER_DELIMITER = ".";
	public static final String COLUMNFAMILY_QUALIFIER_DELIMITER_PATTERN = "\\.";

	public static final String CONNECTOR_TYPE_VALUE_HBASE = "HBASE";
	public static final String CONNECTOR_VERSION_VALUE_143 = "1.4.3";
	public static final String CONNECTOR_VERSION_VALUE_211 = "2.1.1"; // support later

	public static final String CONNECTOR_HBASE_CLIENT_PARAM_PREFIX = "hbase.*";
	public static final String CONNECTOR_HBASE_TABLE_NAME = "tableName".toLowerCase();

	@Override
	public void validate(DescriptorProperties properties) {
		super.validate(properties);
		properties.validateValue(CONNECTOR_TYPE, CONNECTOR_TYPE_VALUE_HBASE, false);
		validateVersion(properties);
	}

	private void validateVersion(DescriptorProperties properties) {
		final List<String> versions = Arrays.asList(
				CONNECTOR_VERSION_VALUE_143);
		properties.validateEnumValues(CONNECTOR_VERSION, false, versions);
	}

}
