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

package org.apache.hudi.utilities.sources;

import org.apache.hudi.DataSourceUtils;
import org.apache.hudi.common.config.TypedProperties;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.utilities.schema.SchemaProvider;
import org.apache.hudi.utilities.sources.helpers.CloudObjectMetadata;
import org.apache.hudi.utilities.sources.helpers.IncrSourceHelper.MissingCheckpointStrategy;
import org.apache.hudi.utilities.sources.helpers.gcs.GcsObjectDataFetcher;
import org.apache.hudi.utilities.sources.helpers.gcs.GcsObjectMetadataFetcher;
import org.apache.hudi.utilities.sources.helpers.gcs.QueryInfo;

import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

import static org.apache.hudi.common.util.StringUtils.isNullOrEmpty;
import static org.apache.hudi.utilities.config.CloudSourceConfig.DATAFILE_FORMAT;
import static org.apache.hudi.utilities.config.CloudSourceConfig.ENABLE_EXISTS_CHECK;
import static org.apache.hudi.utilities.config.HoodieIncrSourceConfig.HOODIE_SRC_BASE_PATH;
import static org.apache.hudi.utilities.config.HoodieIncrSourceConfig.NUM_INSTANTS_PER_FETCH;
import static org.apache.hudi.utilities.config.HoodieIncrSourceConfig.SOURCE_FILE_FORMAT;
import static org.apache.hudi.utilities.sources.helpers.IncrSourceHelper.calculateBeginAndEndInstants;
import static org.apache.hudi.utilities.sources.helpers.IncrSourceHelper.getMissingCheckpointStrategy;

/**
 * An incremental source that detects new data in a source table containing metadata about GCS files,
 * downloads the actual content of these files from GCS and stores them as records into a destination table.
 * <p>
 * You should set spark.driver.extraClassPath in spark-defaults.conf to
 * look like below WITHOUT THE NEWLINES (or give the equivalent as CLI options if in cluster mode):
 * (mysql-connector at the end is only needed if Hive Sync is enabled and Mysql is used for Hive Metastore).

 absolute_path_to/protobuf-java-3.21.1.jar:absolute_path_to/failureaccess-1.0.1.jar:
 absolute_path_to/31.1-jre/guava-31.1-jre.jar:
 absolute_path_to/mysql-connector-java-8.0.30.jar

 This class can be invoked via spark-submit as follows. There's a bunch of optional hive sync flags at the end.
  $ bin/spark-submit \
  --packages com.google.cloud:google-cloud-pubsub:1.120.0 \
  --packages com.google.cloud.bigdataoss:gcs-connector:hadoop2-2.2.7 \
  --driver-memory 4g \
  --executor-memory 4g \
  --class org.apache.hudi.utilities.deltastreamer.HoodieDeltaStreamer \
  absolute_path_to/hudi-utilities-bundle_2.12-0.13.0-SNAPSHOT.jar \
  --source-class org.apache.hudi.utilities.sources.GcsEventsHoodieIncrSource \
  --op INSERT \
  --hoodie-conf hoodie.deltastreamer.source.hoodieincr.file.format="parquet" \
  --hoodie-conf hoodie.deltastreamer.source.cloud.data.select.file.extension="jsonl" \
  --hoodie-conf hoodie.deltastreamer.source.cloud.data.datafile.format="json" \
  --hoodie-conf hoodie.deltastreamer.source.cloud.data.select.relpath.prefix="country" \
  --hoodie-conf hoodie.deltastreamer.source.cloud.data.ignore.relpath.prefix="blah" \
  --hoodie-conf hoodie.deltastreamer.source.cloud.data.ignore.relpath.substring="blah" \
  --hoodie-conf hoodie.datasource.write.recordkey.field=id \
  --hoodie-conf hoodie.datasource.write.partitionpath.field= \
  --filter-dupes \
  --hoodie-conf hoodie.datasource.write.insert.drop.duplicates=true \
  --hoodie-conf hoodie.combine.before.insert=true \
  --source-ordering-field id \
  --table-type COPY_ON_WRITE \
  --target-base-path file:\/\/\/absolute_path_to/data-gcs \
  --target-table gcs_data \
  --continuous \
  --source-limit 100 \
  --min-sync-interval-seconds 60 \
  --hoodie-conf hoodie.deltastreamer.source.hoodieincr.path=file:\/\/\/absolute_path_to/meta-gcs \
  --hoodie-conf hoodie.deltastreamer.source.hoodieincr.missing.checkpoint.strategy=READ_UPTO_LATEST_COMMIT \
  --enable-hive-sync \
  --hoodie-conf hoodie.datasource.hive_sync.database=default \
  --hoodie-conf hoodie.datasource.hive_sync.table=gcs_data \
 */
public class GcsEventsHoodieIncrSource extends HoodieIncrSource {

  private final String srcPath;
  private final boolean checkIfFileExists;
  private final int numInstantsPerFetch;

  private final MissingCheckpointStrategy missingCheckpointStrategy;
  private final GcsObjectMetadataFetcher gcsObjectMetadataFetcher;
  private final GcsObjectDataFetcher gcsObjectDataFetcher;

  private static final Logger LOG = LoggerFactory.getLogger(GcsEventsHoodieIncrSource.class);

  public GcsEventsHoodieIncrSource(TypedProperties props, JavaSparkContext jsc, SparkSession spark,
                                   SchemaProvider schemaProvider) {

    this(props, jsc, spark, schemaProvider,
        new GcsObjectMetadataFetcher(props, getSourceFileFormat(props)),
        new GcsObjectDataFetcher(props, props.getString(DATAFILE_FORMAT.key(), DATAFILE_FORMAT.defaultValue()))
    );
  }

  GcsEventsHoodieIncrSource(TypedProperties props, JavaSparkContext jsc, SparkSession spark,
                            SchemaProvider schemaProvider, GcsObjectMetadataFetcher gcsObjectMetadataFetcher, GcsObjectDataFetcher gcsObjectDataFetcher) {
    super(props, jsc, spark, schemaProvider);

    DataSourceUtils.checkRequiredProperties(props, Collections.singletonList(HOODIE_SRC_BASE_PATH.key()));
    srcPath = props.getString(HOODIE_SRC_BASE_PATH.key());
    missingCheckpointStrategy = getMissingCheckpointStrategy(props);
    numInstantsPerFetch = props.getInteger(NUM_INSTANTS_PER_FETCH.key(), NUM_INSTANTS_PER_FETCH.defaultValue());
    checkIfFileExists = props.getBoolean(ENABLE_EXISTS_CHECK.key(), ENABLE_EXISTS_CHECK.defaultValue());

    this.gcsObjectMetadataFetcher = gcsObjectMetadataFetcher;
    this.gcsObjectDataFetcher = gcsObjectDataFetcher;

    LOG.info("srcPath: " + srcPath);
    LOG.info("missingCheckpointStrategy: " + missingCheckpointStrategy);
    LOG.info("numInstantsPerFetch: " + numInstantsPerFetch);
    LOG.info("checkIfFileExists: " + checkIfFileExists);
  }

  @Override
  public Pair<Option<Dataset<Row>>, String> fetchNextBatch(Option<String> lastCkptStr, long sourceLimit) {
    QueryInfo queryInfo = getQueryInfo(lastCkptStr);

    if (queryInfo.areStartAndEndInstantsEqual()) {
      LOG.info("Already caught up. Begin Checkpoint was: " + queryInfo.getStartInstant());
      return Pair.of(Option.empty(), queryInfo.getStartInstant());
    }

    Dataset<Row> cloudObjectMetadataDF = queryInfo.initCloudObjectMetadata(srcPath, sparkSession);

    if (cloudObjectMetadataDF.isEmpty()) {
      LOG.info("Source of file names is empty. Returning empty result and endInstant: "
              + queryInfo.getEndInstant());
      return Pair.of(Option.empty(), queryInfo.getEndInstant());
    }

    return extractData(queryInfo, cloudObjectMetadataDF);
  }

  private Pair<Option<Dataset<Row>>, String> extractData(QueryInfo queryInfo, Dataset<Row> cloudObjectMetadataDF) {
    List<CloudObjectMetadata> cloudObjectMetadata = gcsObjectMetadataFetcher.getGcsObjectMetadata(sparkContext, cloudObjectMetadataDF, checkIfFileExists);
    Option<Dataset<Row>> fileDataRows = gcsObjectDataFetcher.getCloudObjectDataDF(sparkSession, cloudObjectMetadata, props);
    return Pair.of(fileDataRows, queryInfo.getEndInstant());
  }

  private QueryInfo getQueryInfo(Option<String> lastCkptStr) {
    Option<String> beginInstant = getBeginInstant(lastCkptStr);

    Pair<String, Pair<String, String>> queryInfoPair = calculateBeginAndEndInstants(
        sparkContext, srcPath, numInstantsPerFetch, beginInstant, missingCheckpointStrategy
    );

    QueryInfo queryInfo = new QueryInfo(queryInfoPair.getLeft(), queryInfoPair.getRight().getLeft(),
            queryInfoPair.getRight().getRight());

    if (LOG.isDebugEnabled()) {
      queryInfo.logDetails();
    }

    return queryInfo;
  }

  private Option<String> getBeginInstant(Option<String> lastCheckpoint) {
    if (lastCheckpoint.isPresent() && !isNullOrEmpty(lastCheckpoint.get())) {
      return lastCheckpoint;
    }

    return Option.empty();
  }

  private static String getSourceFileFormat(TypedProperties props) {
    return props.getString(SOURCE_FILE_FORMAT.key(), SOURCE_FILE_FORMAT.defaultValue());
  }

}
