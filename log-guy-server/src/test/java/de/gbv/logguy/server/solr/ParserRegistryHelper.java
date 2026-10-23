package de.gbv.logguy.server.solr;

import de.gbv.logguy.server.script.ParserRegistry;
import de.gbv.logguy.server.script.ParserScript;
import de.gbv.logguy.server.script.ScriptRuntime;

/**
 * Loads the MyCoRe template for tests.
 */
final class ParserRegistryHelper {

    private ParserRegistryHelper() {
    }

    static ParserScript mycore(ScriptRuntime runtime) throws Exception {
        return ParserScript.load(runtime, "mycore-log4j", ParserRegistry.template("mycore-log4j"));
    }
}
