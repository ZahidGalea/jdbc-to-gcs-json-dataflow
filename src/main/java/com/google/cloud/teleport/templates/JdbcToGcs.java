/*
 * Copyright (C) 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.teleport.templates;

import com.google.api.services.bigquery.model.TableRow;
import com.google.cloud.teleport.io.DynamicJdbcIO;
import com.google.cloud.teleport.templates.common.JdbcConverters;
import com.google.cloud.teleport.util.KMSEncryptedNestedValueProvider;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.io.gcp.bigquery.TableRowJsonCoder;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.ValueProvider;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.values.PCollection;

/**
 * A template that copies data from a relational database using JDBC to an existing GCS Bucket.
 */
public class JdbcToGcs {

    private static ValueProvider<String> maybeDecrypt(
            ValueProvider<String> unencryptedValue, ValueProvider<String> kmsKey) {
        return new KMSEncryptedNestedValueProvider(unencryptedValue, kmsKey);
    }

    /**
     * Main entry point for executing the pipeline. This will run the pipeline asynchronously. If
     * blocking execution is required, use the {@link
     * JdbcToGcs#run(JdbcConverters.JdbcToGcsOptions)} method to start the pipeline and
     * invoke {@code result.waitUntilFinish()} on the {@link PipelineResult}
     *
     * @param args The command-line arguments to the pipeline.
     */
    public static void main(String[] args) {

        // Parse the user options passed from the command-line
        JdbcConverters.JdbcToGcsOptions options =
                PipelineOptionsFactory.fromArgs(args)
                        .withValidation()
                        .as(JdbcConverters.JdbcToGcsOptions.class);

        run(options);
    }

    /**
     * Runs the pipeline with the supplied options.
     *
     * @param options The execution parameters to the pipeline.
     * @return The result of the pipeline execution.
     */
    private static PipelineResult run(JdbcConverters.JdbcToGcsOptions options) {
        // Create the pipeline
        Pipeline pipeline = Pipeline.create(options);

        /*
         * Steps: 1) Read records via JDBC and convert to TableRow via RowMapper
         *        2) TableRow to String
         *        3) Write to Cloud Storage
         */
        PCollection<TableRow> tableRows = pipeline
                /*
                 * Step 1: Read records via JDBC and convert to TableRow
                 *         via {@link org.apache.beam.sdk.io.jdbc.JdbcIO.RowMapper}
                 */
                .apply(
                        "Read from JdbcIO",
                        DynamicJdbcIO.<TableRow>read()
                                .withDataSourceConfiguration(
                                        DynamicJdbcIO.DynamicDataSourceConfiguration.create(
                                                        options.getDriverClassName(),
                                                        maybeDecrypt(options.getConnectionURL(), options.getKMSEncryptionKey()))
                                                .withUsername(
                                                        maybeDecrypt(options.getUsername(), options.getKMSEncryptionKey()))
                                                .withPassword(
                                                        maybeDecrypt(options.getPassword(), options.getKMSEncryptionKey()))
                                                .withDriverJars(options.getDriverJars())
                                                .withConnectionProperties(options.getConnectionProperties()))
                                .withQuery(options.getQuery())
                                .withCoder(TableRowJsonCoder.of())
                                .withRowMapper(JdbcConverters.getResultSetToTableRow()));


        PCollection<String> tableString = tableRows.apply("Convert to String", MapElements.via(
                new SimpleFunction<TableRow, String>() {

                    @Override
                    public String apply(TableRow row) {
                        return row.toString();
                    }
                }));

        tableString.apply("Write to Storage", TextIO.write().withDelimiter(new char[]{'|'}).to(options.getOutputBucketPath().toString() + Runtime.getRuntime() + options.getOutputFileName())
        );


        // Execute the pipeline and return the result.
        return pipeline.run();
    }
}
