package de.gbv.logguy.server.script;

/**
 * A field declared under {@code fields} in a parser script.
 *
 * @param name name as used in API and GUI
 * @param type the type
 * @param facet whether the GUI offers facets for it
 */
public record FieldDefinition(String name, FieldType type, boolean facet) {

    /**
     * Name of the Solr field.
     *
     * @return name plus type suffix
     */
    public String solrField() {
        return type.solrField(name);
    }
}
