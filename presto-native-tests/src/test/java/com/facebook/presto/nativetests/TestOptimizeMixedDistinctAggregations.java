
/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.nativetests;

import com.facebook.presto.testing.QueryRunner;
import com.google.common.collect.ImmutableMap;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static com.facebook.presto.nativeworker.PrestoNativeQueryRunnerUtils.nativeHiveQueryRunnerBuilder;
import static com.facebook.presto.sidecar.NativeSidecarPluginQueryRunnerUtils.setupNativeSidecarPlugin;
import static java.lang.Boolean.parseBoolean;

public class TestOptimizeMixedDistinctAggregations
        extends AbstractTestAggregationsNative
{
    private String storageFormat;
    private boolean charNToVarcharImplicitCast;
    private boolean sidecarEnabled;

    @BeforeClass
    @Override
    public void init()
            throws Exception
    {
        storageFormat = System.getProperty("storageFormat", "PARQUET");
        sidecarEnabled = parseBoolean(System.getProperty("sidecarEnabled", "true"));
        charNToVarcharImplicitCast = getCharNToVarcharImplicitCastForTest(
                sidecarEnabled, parseBoolean(System.getProperty("charNToVarcharImplicitCast", "false")));
        super.init(storageFormat, charNToVarcharImplicitCast, sidecarEnabled);
        super.init();
    }

    @Override
    protected QueryRunner createQueryRunner() throws Exception
    {
        QueryRunner queryRunner = nativeHiveQueryRunnerBuilder()
                .setStorageFormat(storageFormat)
                .setAddStorageFormatToPath(true)
                .setImplicitCastCharNToVarchar(charNToVarcharImplicitCast)
                .setUseThrift(true)
                .setCoordinatorSidecarEnabled(sidecarEnabled)
                .setExtraCoordinatorProperties(
                        ImmutableMap.of("optimizer.optimize-mixed-distinct-aggregations", "true"))
                .build();
        if (sidecarEnabled) {
            setupNativeSidecarPlugin(queryRunner);
        }
        return queryRunner;
    }

    @Override
    protected void createTables()
    {
        NativeTestsUtils.createTables(storageFormat);
    }

    @Test
    public void testIssue27860ApproxPercentileWithComputedConstantAndDistinct()
    {
        // Issue #27860: when optimize_mixed_distinct_aggregations=true, combining
        // approx_percentile with a computed constant percentile (e.g. CAST(90 AS DOUBLE)/100)
        // and COUNT(DISTINCT ...) causes Velox to throw because the optimizer's GroupIdNode
        // NULLs out the percentile variable for grouping-set-1 rows.
        // Velox sees percentile=0.9 for g0 rows and percentile=0 (NULL coerced) for g1 rows
        // within the same batch, violating its constant-percentile invariant.
        //
        // Expected error (bug present):
        //   "Percentile argument must be constant for all input rows"
        //
        // This test PASSES while the bug exists and must be changed to assertQuerySucceeds after fix.
        @Language("SQL") String sql =
                "SELECT approx_percentile(CAST(totalprice AS BIGINT), CAST(90 AS DOUBLE) / 100)," +
                "       count(distinct custkey) " +
                "FROM orders " +
                "GROUP BY orderstatus";

        assertQueryFails(sql, ".*Percentile argument must be constant for all input rows.*", true);
    }
}
