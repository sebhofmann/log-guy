package de.gbv.logguy.server.script;

/**
 * Takes over the scripts of a kind after one of them was activated, removed or reloaded.
 */
@FunctionalInterface
public interface ScriptListener {

    /**
     * Called after the active scripts of a kind changed.
     *
     * @param kind the kind
     * @param store the store with the active scripts
     */
    void changed(ScriptKind kind, ScriptStore store);
}
