/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.metadata;

import org.apache.hudi.avro.model.HoodieMetadataColumnStats;
import org.apache.hudi.common.bloom.BloomFilter;
import org.apache.hudi.common.config.HoodieMetadataConfig;
import org.apache.hudi.common.data.HoodieData;
import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieRecordGlobalLocation;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.exception.HoodieMetadataException;
import org.apache.hudi.expression.Expression;
import org.apache.hudi.internal.schema.Types;
import org.apache.hudi.storage.StoragePath;
import org.apache.hudi.storage.StoragePathInfo;

import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.apache.hudi.common.util.ValidationUtils.checkArgument;
import static org.apache.hudi.common.util.ValidationUtils.checkState;

/**
 * Interface that supports querying various pieces of metadata about a hudi table.
 */
public interface HoodieTableMetadata extends Serializable, AutoCloseable {

  // Table name suffix
  String METADATA_TABLE_NAME_SUFFIX = "_metadata";
  /**
   * Timestamp for a commit when the base dataset had not had any commits yet. this is < than even
   * {@link org.apache.hudi.common.table.timeline.HoodieTimeline#INIT_INSTANT_TS}, such that the metadata table
   * can be prepped even before bootstrap is done.
   */
  String SOLO_COMMIT_TIMESTAMP = "00000000000000";
  // Key for the record which saves list of all partitions
  String RECORDKEY_PARTITION_LIST = "__all_partitions__";
  // The partition name used for non-partitioned tables
  String NON_PARTITIONED_NAME = ".";
  String EMPTY_PARTITION_NAME = "";

  /**
   * Return the base-path of the Metadata Table for the given Dataset identified by base-path
   */
  static String getMetadataTableBasePath(String dataTableBasePath) {
    return dataTableBasePath + StoragePath.SEPARATOR + HoodieTableMetaClient.METADATA_TABLE_FOLDER_PATH;
  }

  /**
   * Return the base-path of the Metadata Table for the given Dataset identified by base-path
   */
  static StoragePath getMetadataTableBasePath(StoragePath dataTableBasePath) {
    return new StoragePath(dataTableBasePath, HoodieTableMetaClient.METADATA_TABLE_FOLDER_PATH);
  }

  /**
   * Returns the base path of the Dataset provided the base-path of the Metadata Table of this
   * Dataset
   */
  static String getDataTableBasePathFromMetadataTable(String metadataTableBasePath) {
    checkArgument(isMetadataTable(metadataTableBasePath));
    return metadataTableBasePath.substring(0, metadataTableBasePath.lastIndexOf(HoodieTableMetaClient.METADATA_TABLE_FOLDER_PATH) - 1);
  }

  /**
   * Return the base path of the dataset.
   *
   * @param metadataTableBasePath The base path of the metadata table
   */
  static String getDatasetBasePath(String metadataTableBasePath) {
    int endPos = metadataTableBasePath.lastIndexOf(StoragePath.SEPARATOR + HoodieTableMetaClient.METADATA_TABLE_FOLDER_PATH);
    checkState(endPos != -1, metadataTableBasePath + " should be base path of the metadata table");
    return metadataTableBasePath.substring(0, endPos);
  }

  /**
   * Returns {@code True} if the given path contains a metadata table.
   *
   * @param basePath The base path to check
   */
  static boolean isMetadataTable(String basePath) {
    if (basePath == null || basePath.isEmpty()) {
      return false;
    }
    if (basePath.endsWith(StoragePath.SEPARATOR)) {
      basePath = basePath.substring(0, basePath.length() - 1);
    }
    return basePath.endsWith(HoodieTableMetaClient.METADATA_TABLE_FOLDER_PATH);
  }

  static HoodieTableMetadata create(HoodieEngineContext engineContext, HoodieMetadataConfig metadataConfig, String datasetBasePath) {
    return create(engineContext, metadataConfig, datasetBasePath, false);
  }

  static HoodieTableMetadata create(HoodieEngineContext engineContext, HoodieMetadataConfig metadataConfig, String datasetBasePath, boolean reuse) {
    if (metadataConfig.isEnabled()) {
      HoodieBackedTableMetadata metadata = createHoodieBackedTableMetadata(engineContext, metadataConfig, datasetBasePath, reuse);
      // If the MDT is not initialized then we fallback to FSBackedTableMetadata
      if (metadata.isMetadataTableInitialized()) {
        return metadata;
      }
    }

    return createFSBackedTableMetadata(engineContext, metadataConfig, datasetBasePath);
  }

  static FileSystemBackedTableMetadata createFSBackedTableMetadata(HoodieEngineContext engineContext,
                                                                   HoodieMetadataConfig metadataConfig,
                                                                   String datasetBasePath) {
    return new FileSystemBackedTableMetadata(engineContext, engineContext.getStorageConf(),
        datasetBasePath, metadataConfig.shouldAssumeDatePartitioning());
  }

  static HoodieBackedTableMetadata createHoodieBackedTableMetadata(HoodieEngineContext engineContext,
                                                                   HoodieMetadataConfig metadataConfig,
                                                                   String datasetBasePath,
                                                                   boolean reuse) {
    return new HoodieBackedTableMetadata(engineContext, metadataConfig, datasetBasePath, reuse);
  }

  /**
   * Fetch all the files at the given partition path, per the latest snapshot of the metadata.
   */
  List<StoragePathInfo> getAllFilesInPartition(StoragePath partitionPath) throws IOException;

  /**
   * Retrieve the paths of partitions under the provided sub-directories,
   * and try to filter these partitions using the provided {@link Expression}.
   */
  List<String> getPartitionPathWithPathPrefixUsingFilterExpression(List<String> relativePathPrefixes,
                                                                   Types.RecordType partitionFields,
                                                                   Expression expression) throws IOException;

  /**
   * Fetches all partition paths that are the sub-directories of the list of provided (relative) paths.
   * <p>
   * E.g., Table has partition 4 partitions:
   * year=2022/month=08/day=30, year=2022/month=08/day=31, year=2022/month=07/day=03, year=2022/month=07/day=04
   * The relative path "year=2022" returns all partitions, while the relative path
   * "year=2022/month=07" returns only two partitions.
   */
  List<String> getPartitionPathWithPathPrefixes(List<String> relativePathPrefixes) throws IOException;

  /**
   * Fetch list of all partition paths, per the latest snapshot of the metadata.
   */
  List<String> getAllPartitionPaths() throws IOException;

  /**
   * Fetch all files for given partition paths.
   *
   * NOTE: Absolute partition paths are expected here
   */
  Map<String, List<StoragePathInfo>> getAllFilesInPartitions(Collection<String> partitionPaths)
      throws IOException;

  /**
   * Get the bloom filter for the FileID from the metadata table.
   *
   * @param partitionName - Partition name
   * @param fileName      - File name for which bloom filter needs to be retrieved
   * @return BloomFilter if available, otherwise empty
   * @throws HoodieMetadataException
   */
  Option<BloomFilter> getBloomFilter(final String partitionName, final String fileName)
      throws HoodieMetadataException;

  /**
   * Get bloom filters for files from the metadata table index.
   *
   * @param partitionNameFileNameList - List of partition and file name pair for which bloom filters need to be retrieved
   * @return Map of partition file name pair to its bloom filter
   * @throws HoodieMetadataException
   */
  Map<Pair<String, String>, BloomFilter> getBloomFilters(final List<Pair<String, String>> partitionNameFileNameList)
      throws HoodieMetadataException;

  /**
   * Get column stats for files from the metadata table index.
   *
   * @param partitionNameFileNameList - List of partition and file name pair for which bloom filters need to be retrieved
   * @param columnName                - Column name for which stats are needed
   * @return Map of partition and file name pair to its column stats
   * @throws HoodieMetadataException
   */
  Map<Pair<String, String>, HoodieMetadataColumnStats> getColumnStats(final List<Pair<String, String>> partitionNameFileNameList, final String columnName)
      throws HoodieMetadataException;

  /**
   * Returns the location of record keys which are found in the record index.
   * Records that are not found are ignored and wont be part of map object that is returned.
   */
  Map<String, HoodieRecordGlobalLocation> readRecordIndex(List<String> recordKeys);

  /**
   * Fetch records by key prefixes. Key prefix passed is expected to match the same prefix as stored in Metadata table partitions. For eg, in case of col stats partition,
   * actual keys in metadata partition is encoded values of column name, partition name and file name. So, key prefixes passed to this method is expected to be encoded already.
   *
   * @param keyPrefixes   list of key prefixes for which interested records are looked up for.
   * @param partitionName partition name in metadata table where the records are looked up for.
   * @return {@link HoodieData} of {@link HoodieRecord}s with records matching the passed in key prefixes.
   */
  HoodieData<HoodieRecord<HoodieMetadataPayload>> getRecordsByKeyPrefixes(List<String> keyPrefixes,
                                                                          String partitionName,
                                                                          boolean shouldLoadInMemory);

  /**
   * Get the instant time to which the metadata is synced w.r.t data timeline.
   */
  Option<String> getSyncedInstantTime();

  /**
   * Returns the timestamp of the latest compaction.
   */
  Option<String> getLatestCompactionTime();

  /**
   * Clear the states of the table metadata.
   */
  void reset();

  /**
   * Returns the number of shards in a metadata table partition.
   */
  int getNumFileGroupsForPartition(MetadataPartitionType partition);
}
