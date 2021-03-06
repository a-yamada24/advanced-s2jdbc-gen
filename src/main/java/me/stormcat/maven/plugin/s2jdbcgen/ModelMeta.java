/**
 * 
 */
package me.stormcat.maven.plugin.s2jdbcgen;

import me.stormcat.maven.plugin.s2jdbcgen.meta.Table;

import org.seasar.util.lang.StringUtil;

/**
 * @author a.yamada
 *
 */
public class ModelMeta {

    private final Table table;
    
    private final String entityName;
    
    private final String abstractEntityName;
    
    private final String serviceName;
    
    private final String abstractServiceName;
    
    private final String namesName;
    
    private final String fieldName;
    
    public ModelMeta(Table table) {
        this.table = table;
        this.entityName = StringUtil.camelize(table.getName());
        this.abstractEntityName = String.format("Abstract%s", entityName);
        this.serviceName = String.format("%sService", this.entityName);
        this.abstractServiceName = String.format("Abstract%sService", this.entityName);
        this.namesName = String.format("%sNames", entityName);
        this.fieldName = String.format("%s%s", entityName.substring(0, 1).toLowerCase(), entityName.substring(1, entityName.length()));
    }

    public Table getTable() {
        return table;
    }

    public String getEntityName() {
        return entityName;
    }

    public String getAbstractEntityName() {
        return abstractEntityName;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getAbstractServiceName() {
        return abstractServiceName;
    }

    public String getNamesName() {
        return namesName;
    }

    public String getFieldName() {
        return fieldName;
    }
    
    public String getEntityFieldName() {
        return String.format("%s%s", entityName.substring(0, 1).toLowerCase(), entityName.substring(1, entityName.length()));
    }
    
}
