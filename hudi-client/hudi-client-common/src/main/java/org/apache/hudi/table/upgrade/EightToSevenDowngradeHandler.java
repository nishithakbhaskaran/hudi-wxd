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

package org.apache.hudi.table.upgrade;

import org.apache.hudi.common.config.ConfigProperty;
import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.HoodieCommitMetadata;
import org.apache.hudi.common.table.HoodieTableConfig;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.HoodieTableVersion;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.timeline.InstantFileNameGenerator;
import org.apache.hudi.common.table.timeline.versioning.v1.ActiveTimelineV1;
import org.apache.hudi.common.table.timeline.versioning.v1.CommitMetadataSerDeV1;
import org.apache.hudi.common.table.timeline.versioning.v2.ActiveTimelineV2;
import org.apache.hudi.common.table.timeline.versioning.v2.CommitMetadataSerDeV2;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.keygen.constant.KeyGeneratorOptions;
import org.apache.hudi.keygen.constant.KeyGeneratorType;
import org.apache.hudi.metadata.HoodieTableMetadata;
import org.apache.hudi.metadata.HoodieTableMetadataUtil;
import org.apache.hudi.storage.HoodieStorage;
import org.apache.hudi.storage.StoragePath;
import org.apache.hudi.table.HoodieTable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.hudi.common.table.HoodieTableConfig.TABLE_METADATA_PARTITIONS;
import static org.apache.hudi.common.table.timeline.HoodieInstant.UNDERSCORE;
import static org.apache.hudi.common.table.timeline.TimelineLayout.TIMELINE_LAYOUT_V1;
import static org.apache.hudi.common.table.timeline.TimelineLayout.TIMELINE_LAYOUT_V2;
import static org.apache.hudi.metadata.MetadataPartitionType.BLOOM_FILTERS;
import static org.apache.hudi.metadata.MetadataPartitionType.COLUMN_STATS;
import static org.apache.hudi.metadata.MetadataPartitionType.FILES;
import static org.apache.hudi.metadata.MetadataPartitionType.RECORD_INDEX;
import static org.apache.hudi.table.upgrade.UpgradeDowngradeUtils.convertCompletionTimeToEpoch;

/**
 * Version 7 is going to be placeholder version for bridge release 0.16.0.
 * Version 8 is the placeholder version to track 1.x.
 */
public class EightToSevenDowngradeHandler implements DowngradeHandler {

  private static final Logger LOG = LoggerFactory.getLogger(EightToSevenDowngradeHandler.class);
  private static final Set<String> SUPPORTED_METADATA_PARTITION_PATHS = getSupportedMetadataPartitionPaths();

  @Override
  public Map<ConfigProperty, String> downgrade(HoodieWriteConfig config, HoodieEngineContext context, String instantTime, SupportsUpgradeDowngrade upgradeDowngradeHelper) {
    final HoodieTable table = upgradeDowngradeHelper.getTable(config, context);
    Map<ConfigProperty, String> tablePropsToAdd = new HashMap<>();
    UpgradeDowngradeUtils.runCompaction(table, context, config, upgradeDowngradeHelper);
    UpgradeDowngradeUtils.syncCompactionRequestedFileToAuxiliaryFolder(table);

    HoodieTableMetaClient metaClient = HoodieTableMetaClient.builder().setConf(context.getStorageConf().newInstance()).setBasePath(config.getBasePath()).build();
    List<HoodieInstant> instants = new ArrayList<>();
    try {
      // We need to move all the instants - not just completed ones.
      instants = metaClient.scanHoodieInstantsFromFileSystem(metaClient.getTimelinePath(),
          ActiveTimelineV2.VALID_EXTENSIONS_IN_ACTIVE_TIMELINE, false);
    } catch (IOException ioe) {
      LOG.error("Failed to get instants from filesystem", ioe);
      throw new HoodieIOException("Failed to get instants from filesystem", ioe);
    }

    if (!instants.isEmpty()) {
      InstantFileNameGenerator instantFileNameGenerator = metaClient.getInstantFileNameGenerator();
      CommitMetadataSerDeV2 commitMetadataSerDeV2 = new CommitMetadataSerDeV2();
      CommitMetadataSerDeV1 commitMetadataSerDeV1 = new CommitMetadataSerDeV1();
      ActiveTimelineV1 activeTimelineV1 = new ActiveTimelineV1(metaClient);
      context.map(instants, instant -> {
        String fileName = instantFileNameGenerator.getFileName(instant);
        // Rename the metadata file name from the ${instant_time}_${completion_time}.action[.state] format in version 1.x to the ${instant_time}.action[.state] format in version 0.x.
        StoragePath fromPath = new StoragePath(TIMELINE_LAYOUT_V2.getTimelinePathProvider().getTimelinePath(
            metaClient.getTableConfig(), metaClient.getBasePath()), fileName);
        long modificationTime = instant.isCompleted() ? convertCompletionTimeToEpoch(instant) : -1;
        StoragePath toPath = new StoragePath(TIMELINE_LAYOUT_V1.getTimelinePathProvider().getTimelinePath(
            metaClient.getTableConfig(), metaClient.getBasePath()), fileName.replaceAll(UNDERSCORE + "\\d+", ""));
        boolean success = true;
        if (fileName.contains(UNDERSCORE)) {
          try {
            if (instant.getAction().equals(HoodieTimeline.COMMIT_ACTION) || instant.getAction().equals(HoodieTimeline.DELTA_COMMIT_ACTION)) {
              HoodieCommitMetadata commitMetadata =
                  commitMetadataSerDeV2.deserialize(instant, metaClient.getActiveTimeline().getInstantDetails(instant).get(), HoodieCommitMetadata.class);
              Option<byte[]> data = commitMetadataSerDeV1.serialize(commitMetadata);
              String toPathStr = toPath.toUri().toString();
              activeTimelineV1.createFileInMetaPath(toPathStr, data, true);
              /**
               * When we downgrade the table from 1.0 to 0.x, it is important to set the modification
               * timestamp of the 0.x completed instant to match the completion time of the
               * corresponding 1.x instant. Otherwise,  log files in previous file slices could
               * be wrongly attributed to latest file slice for 1.0 readers.
               * (see HoodieFileGroup.getBaseInstantTime)
               */
              if (modificationTime > 0) {
                metaClient.getStorage().setModificationTime(toPath, modificationTime);
              }
              metaClient.getStorage().deleteFile(fromPath);
            } else {
              success = metaClient.getStorage().rename(fromPath, toPath);
            }
            // TODO: We need to rename the action-related part of the metadata file name here when we bring separate action name for clustering/compaction in 1.x as well.
            if (!success) {
              throw new HoodieIOException("an error that occurred while renaming " + fromPath + " to: " + toPath);
            }
            return true;
          } catch (IOException e) {
            LOG.error("Can not to complete the downgrade from version eight to version seven. The reason for failure is {}", e.getMessage());
            throw new HoodieException(e);
          }
        } else {
          success = metaClient.getStorage().rename(fromPath, toPath);
        }
        return false;
      }, instants.size());
    }

    downgradePartitionFields(config, context, upgradeDowngradeHelper, tablePropsToAdd);
    // Prepare parameters.
    if (metaClient.getTableConfig().isMetadataTableAvailable()) {
      // Delete unsupported metadata partitions in table version 7.
      downgradeMetadataPartitions(context, metaClient.getStorage(), metaClient, tablePropsToAdd);
      UpgradeDowngradeUtils.updateMetadataTableVersion(context, HoodieTableVersion.SEVEN, metaClient);
    }
    return tablePropsToAdd;
  }

  private static void downgradePartitionFields(HoodieWriteConfig config,
                                               HoodieEngineContext context,
                                               SupportsUpgradeDowngrade upgradeDowngradeHelper,
                                               Map<ConfigProperty, String> tablePropsToAdd) {
    HoodieTableConfig tableConfig = upgradeDowngradeHelper.getTable(config, context).getMetaClient().getTableConfig();
    String keyGenerator = tableConfig.getKeyGeneratorClassName();
    String partitionPathField = config.getString(KeyGeneratorOptions.PARTITIONPATH_FIELD_NAME.key());
    if (keyGenerator != null && partitionPathField != null
        && (keyGenerator.equals(KeyGeneratorType.CUSTOM.getClassName()) || keyGenerator.equals(KeyGeneratorType.CUSTOM_AVRO.getClassName()))) {
      tablePropsToAdd.put(HoodieTableConfig.PARTITION_FIELDS, tableConfig.getPartitionFieldProp());
    }
  }

  static void downgradeMetadataPartitions(HoodieEngineContext context,
                                          HoodieStorage hoodieStorage,
                                          HoodieTableMetaClient metaClient,
                                          Map<ConfigProperty, String> tablePropsToAdd) {
    // Get base path for metadata table.
    StoragePath metadataTableBasePath =
        HoodieTableMetadata.getMetadataTableBasePath(metaClient.getBasePath());

    // Fetch metadata partition paths.
    List<String> metadataPartitions = FSUtils.getAllPartitionPaths(context,
        hoodieStorage,
        metadataTableBasePath,
        false);

    // Delete partitions.
    List<String> validPartitionPaths = deleteMetadataPartition(context, metaClient, metadataPartitions);

    // Clean the configuration.
    tablePropsToAdd.put(TABLE_METADATA_PARTITIONS, String.join(",", validPartitionPaths));
  }

  static List<String> deleteMetadataPartition(HoodieEngineContext context,
                                              HoodieTableMetaClient metaClient,
                                              List<String> metadataPartitions) {
    metadataPartitions.stream()
        .filter(metadataPath -> !SUPPORTED_METADATA_PARTITION_PATHS.contains(metadataPath))
        .forEach(metadataPath ->
            HoodieTableMetadataUtil.deleteMetadataTablePartition(
                metaClient, context, metadataPath, true)
        );

    return metadataPartitions.stream()
        .filter(SUPPORTED_METADATA_PARTITION_PATHS::contains)
        .collect(Collectors.toList());
  }

  private static Set<String> getSupportedMetadataPartitionPaths() {
    Set<String> supportedPartitionPaths = new HashSet<>();
    supportedPartitionPaths.add(BLOOM_FILTERS.getPartitionPath());
    supportedPartitionPaths.add(COLUMN_STATS.getPartitionPath());
    supportedPartitionPaths.add(FILES.getPartitionPath());
    supportedPartitionPaths.add(RECORD_INDEX.getPartitionPath());
    return supportedPartitionPaths;
  }
}
