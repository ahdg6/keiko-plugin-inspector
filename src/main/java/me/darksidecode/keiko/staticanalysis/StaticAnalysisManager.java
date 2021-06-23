/*
 * Copyright 2021 German Vekhorev (DarksideCode)
 *
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

package me.darksidecode.keiko.staticanalysis;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import me.darksidecode.jminima.disassembling.SimpleJavaDisassembler;
import me.darksidecode.jminima.phase.PhaseExecutionException;
import me.darksidecode.jminima.phase.basic.CloseJarFilePhase;
import me.darksidecode.jminima.phase.basic.DisassemblePhase;
import me.darksidecode.jminima.phase.basic.OpenJarFilePhase;
import me.darksidecode.jminima.phase.basic.WalkClassesPhase;
import me.darksidecode.jminima.workflow.Workflow;
import me.darksidecode.jminima.workflow.WorkflowExecutionResult;
import me.darksidecode.keiko.config.GlobalConfig;
import me.darksidecode.keiko.config.InspectionsConfig;
import me.darksidecode.keiko.proxy.Keiko;
import me.darksidecode.keiko.proxy.KeikoLogger;
import me.darksidecode.keiko.registry.IndexedPlugin;
import me.darksidecode.keiko.registry.PluginContext;
import me.darksidecode.keiko.staticanalysis.cache.CacheManager;
import me.darksidecode.keiko.staticanalysis.cache.InspectionCache;
import org.reflections.Reflections;

import java.util.*;

@RequiredArgsConstructor
public class StaticAnalysisManager {

    @NonNull
    private final PluginContext pluginContext;

    @NonNull
    private final CacheManager cacheManager;

    private final Reflections reflections = new Reflections("me.darksidecode.keiko.staticanalysis");

    private final Map<IndexedPlugin, InspectionCache> cachesToPush = new HashMap<>();

    private final Map<IndexedPlugin, List<StaticAnalysisResult>> results = new HashMap<>();

    public boolean inspectAllPlugins() {
        pluginContext.getPlugins().forEach(plugin -> results.put(plugin, new ArrayList<>()));
        Keiko.INSTANCE.getLogger().infoLocalized("staticInspections.beginAll");

        try {
            cacheManager.setup();
        } catch (Exception ex) {
            Keiko.INSTANCE.getLogger().warningLocalized("staticInspections.caches.err");
            Keiko.INSTANCE.getLogger().error("Unhandled exception in: setup", ex);
        }

        for (IndexedPlugin plugin : pluginContext.getPlugins()) {
            boolean failed = inspectPlugin(plugin);

            if (failed && GlobalConfig.getAbortOnError())
                return true; // abort startup

            InspectionCache cacheToPush = cachesToPush.get(plugin);

            if (cacheToPush != null) {
                try {
                    cacheManager.push(plugin.getSha512(), cacheToPush);
                    cachesToPush.remove(plugin);
                } catch (Exception ex) {
                    Keiko.INSTANCE.getLogger().warningLocalized("staticInspections.caches.err");
                    Keiko.INSTANCE.getLogger().error("Unhandled exception in: push [%s]", plugin.getName(), ex);
                }
            }
        }

        try {
            cacheManager.tearDown();
        } catch (Exception ex) {
            Keiko.INSTANCE.getLogger().warningLocalized("staticInspections.caches.err");
            Keiko.INSTANCE.getLogger().error("Unhandled exception in: tearDown", ex);
        }

        return false; // do not abort startup
    }

    public void addResult(@NonNull StaticAnalysisResult result) {
        InspectionCache cache = cachesToPush
                .computeIfAbsent(result.getAnalyzedPlugin(),
                        k -> InspectionCache.createEmptyCache());

        List<StaticAnalysisResult> cachedResults = cache.getAnalysesResults()
                .computeIfAbsent(result.getScannerName(),
                        k -> new ArrayList<>());

        cachedResults.add(result);
        results.get(result.getAnalyzedPlugin()).add(result);
    }

    public boolean processResults() {
        printResults();

        for (List<StaticAnalysisResult> list : results.values()) {
            for (StaticAnalysisResult result : list) {
                String countermeasuresType = result.getType().name().toLowerCase();
                String configScannerName = StaticAnalysis.inspectionNameToConfigName(result.getScannerName());
                String countermeasuresString = InspectionsConfig.getHandle()
                        .get("static." + configScannerName + ".countermeasures." + countermeasuresType);

                Countermeasures countermeasures = Countermeasures.fromString(countermeasuresString);

                if (countermeasures.getAbortStartupFunc().apply(result))
                    return true; // yes, abort startup
            }
        }

        return false; // no, do not abort startup
    }

    private void printResults() {
        int warningsTotal = 0, criticalTotal = 0;

        for (IndexedPlugin plugin : results.keySet()) {
            List<StaticAnalysisResult> pluginResults = results.get(plugin);
            int warnings = 0, critical = 0;

            for (StaticAnalysisResult result : pluginResults) {
                if (result.getType() != StaticAnalysisResult.Type.CLEAN    ) warnings++;
                if (result.getType() == StaticAnalysisResult.Type.MALICIOUS) critical++;
            }

            warningsTotal += warnings;
            criticalTotal += critical;

            // Log level depends on whether there was at least a single warning or not.
            // If the plugin is fully clean, then just debug it. Otherwise really warn.
            KeikoLogger.Level logLevel = warnings == 0
                    ? KeikoLogger.Level.DEBUG : KeikoLogger.Level.WARNING;

            Keiko.INSTANCE.getLogger().logLocalized(
                    logLevel, "staticInspections.pluginResults",
                    plugin.getName(), plugin.getJar().getName());

            for (StaticAnalysisResult result : pluginResults) {
                String typeI18nKey = "staticInspections." + result.getType().name().toLowerCase();
                String analysisDescI18nKey = "staticInspections.desc." + result.getScannerName();

                Keiko.INSTANCE.getLogger().logLocalized(logLevel, typeI18nKey);
                Keiko.INSTANCE.getLogger().logLocalized(
                        logLevel, "staticInspections.analysisName", result.getScannerName());
                Keiko.INSTANCE.getLogger().logLocalized(
                        logLevel, analysisDescI18nKey, plugin.getName(), plugin.getJar().getName());
                Keiko.INSTANCE.getLogger().logLocalized(logLevel, "staticInspections.details");

                int counter = 0;

                for (String detail : result.getDetails())
                    Keiko.INSTANCE.getLogger().log(logLevel, "|            %s. %s", ++counter, detail);

                Keiko.INSTANCE.getLogger().log(logLevel, "|            ");
            }

            Keiko.INSTANCE.getLogger().logLocalized(
                    logLevel, "staticInspections.pluginSummary",
                    warningsTotal, criticalTotal, plugin.getName(), plugin.getJar().getName());

            Keiko.INSTANCE.getLogger().log(logLevel, " ");
            Keiko.INSTANCE.getLogger().log(logLevel, " ");
        }

        Keiko.INSTANCE.getLogger().infoLocalized(
                "staticInspections.finishSummary", warningsTotal, criticalTotal);
    }

    private boolean inspectPlugin(IndexedPlugin plugin) {
        return runInspectionWorkflow(plugin, collectInspectors(plugin)); // true = failed, false = succeeded
    }

    private Collection<Class<? extends StaticAnalysis>> collectInspectors(IndexedPlugin plugin) {
        Collection<Class<? extends StaticAnalysis>> inspections = new ArrayList<>();

        for (Class<?> inspectionClass : reflections.getTypesAnnotatedWith(RegisterStaticAnalysis.class)) {
            if (!StaticAnalysis.class.isAssignableFrom(inspectionClass))
                throw new RuntimeException("illegal managed inspection " +
                        "(annotated but invalid type): " + inspectionClass.getName());

            String inspectionName = StaticAnalysis
                    .classToInspectionName((Class<? extends StaticAnalysis>) inspectionClass);
            String configName = StaticAnalysis.inspectionNameToConfigName(inspectionName);

            if (InspectionsConfig.getHandle().get(configName + ".enabled", true)) {
                try {
                    String inputJarPath = plugin.getJar().getAbsolutePath()
                            .replace("\\", "/"); // better Windows compatibility
                    String pluginsFolderPath = Keiko.INSTANCE.getPluginsDir().getAbsolutePath();
                    List<String> exclusions = InspectionsConfig.getHandle()
                            .get(configName + ".exclusions", Collections.emptyList());

                    boolean excluded = false;

                    for (String exclusion : exclusions) {
                        if (exclusion.replace("{plugins_folder}", pluginsFolderPath)
                                .replace("\\", "/") // better Windows compatibility
                                .equals(inputJarPath)) {
                            excluded = true;
                            break;
                        }
                    }

                    if (!excluded)
                        inspections.add((Class<? extends StaticAnalysis>) inspectionClass);
                } catch (Exception ex) {
                    throw new RuntimeException("invalid managed inspection: " + inspectionClass.getName(), ex);
                }
            }
        }

        return inspections;
    }

    private boolean runInspectionWorkflow(IndexedPlugin plugin,
                                          Collection<Class<? extends StaticAnalysis>> inspections) {
        InspectionCache cache = null;

        try {
            cache = cacheManager.fetch(plugin.getSha512());
        } catch (Exception ex) {
            Keiko.INSTANCE.getLogger().warningLocalized("staticInspections.caches.err");
            Keiko.INSTANCE.getLogger().error("Unhandled exception in: fetch [%s]", plugin.getName(), ex);
        }

        if (cache == null)
            cache = InspectionCache.createEmptyCache();

        InspectionCache finalCache = cache;
        Map<String, List<StaticAnalysisResult>> cachedAnalysesResults = finalCache.getAnalysesResults();

        try (Workflow workflow = new Workflow()
                .phase(new OpenJarFilePhase(plugin.getJar()))
                .phase(new DisassemblePhase(SimpleJavaDisassembler.class))) {
            int cached = 0;

            for (Class<? extends StaticAnalysis> inspection : inspections) {
                String inspectionName = StaticAnalysis.classToInspectionName(inspection);
                List<StaticAnalysisResult> cachedResults = cachedAnalysesResults.get(inspectionName);

                if (cachedResults != null) {
                    Keiko.INSTANCE.getLogger().debugLocalized(
                            "staticInspections.caches.resultCached",
                            inspectionName, plugin.getName(), plugin.getJar().getName(), cachedResults.size());

                    results.get(plugin).addAll(cachedResults);
                    cached++;
                } else {
                    Keiko.INSTANCE.getLogger().debugLocalized(
                            "staticInspections.caches.resultNotCached",
                            inspectionName, plugin.getName(), plugin.getJar().getName());

                    workflow.phase(new WalkClassesPhase(inspection));
                    cachesToPush
                            .computeIfAbsent(plugin, k -> finalCache)
                            .getAnalysesResults()
                            .put(inspectionName, new ArrayList<>());
                }
            }

            workflow.phase(new CloseJarFilePhase());

            if (cached != inspections.size()) {
                // At least one inspection has not been cached. Let's fix it!
                WorkflowExecutionResult result = workflow.executeAll();

                if (result != WorkflowExecutionResult.FULL_SUCCESS) {
                    int isFatal = result == WorkflowExecutionResult.FATAL_FAILURE
                            ? 1  // true
                            : 0; // false

                    Keiko.INSTANCE.getLogger().warningLocalized("staticInspections.err",
                            isFatal, plugin.getName(), plugin.getJar().getName());

                    for (PhaseExecutionException error : workflow.getAllErrorsChronological())
                        Keiko.INSTANCE.getLogger().error("JMinima phase execution error:", error);

                    return true; // inspection failed
                }
            }

            Keiko.INSTANCE.getLogger().debugLocalized(
                    "staticInspections.caches.analysisStats",
                    inspections.size(), plugin.getName(), plugin.getJar().getName(), cached);

            return false; // inspection succeeded
        }
    }

}
