package me.stormcat.maven.plugin.s2jdbcgen;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import me.stormcat.maven.plugin.s2jdbcgen.factory.ColumnListBuilder;
import me.stormcat.maven.plugin.s2jdbcgen.meta.Column;
import me.stormcat.maven.plugin.s2jdbcgen.meta.Index;
import me.stormcat.maven.plugin.s2jdbcgen.meta.Table;
import me.stormcat.maven.plugin.s2jdbcgen.util.ConnectionUtil;
import me.stormcat.maven.plugin.s2jdbcgen.util.DriverManagerUtil;
import me.stormcat.maven.plugin.s2jdbcgen.util.FileUtil;
import me.stormcat.maven.plugin.s2jdbcgen.util.StringUtil;
import me.stormcat.maven.plugin.s2jdbcgen.util.VelocityUtil;

import org.seasar.util.sql.PreparedStatementUtil;
import org.seasar.util.sql.ResultSetUtil;
import org.seasar.util.sql.StatementUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;


public class GenerateCodeExecutor {
    
    private static final String SHOW_TABLES = "SHOW TABLES";
    
    private final String genDir;
    
    private final String rootPackage;
    
    private final String host;
    
    private final String schema;
    
    private final String user;
    
    private final String password;
    
    private DelFlag delFlag;
    
    private static final Logger logger = LoggerFactory.getLogger(GenerateCodeExecutor.class);
    
    public GenerateCodeExecutor(String genDir, String rootPackage, String host, String schema, String user, String password) {
        this.genDir = genDir;
        this.rootPackage = rootPackage;
        this.host = host;
        this.schema = schema;
        this.user = user;
        this.password = password;
    }
    
    public void execute() {
        String tableNameColumn = String.format("Tables_in_%s", schema);
        
        DriverManagerUtil.registerDriver("com.mysql.jdbc.Driver");
        
        Connection connection = null;
        PreparedStatement ps = null;
        ResultSet resultSet = null;
        
        logger.info("-----[configuration]-----");
        logger.info(String.format("genDir=%s", genDir));
        logger.info(String.format("rootPackage=%s", rootPackage));
        logger.info(String.format("host=%s", host));
        logger.info(String.format("schema=%s", schema));
        logger.info(String.format("user=%s", user));
        logger.info(String.format("password=%s", password));
        if (delFlag != null) {
            logger.info(String.format("delFlag#name=%s", delFlag.getName()));
            logger.info(String.format("delFlag#delValue=%s", delFlag.isDelValue()));
        }
        logger.info("-------------------------");
        
        logger.info(String.format("%s からのリバースを開始します。", schema));
        Map<String, ModelMeta> metaMap = new LinkedHashMap<String, ModelMeta>();
        try {
            connection = DriverManagerUtil.getConnection(String.format("jdbc:mysql://%s/%s", host, schema), user, password);
            ps = ConnectionUtil.getPreparedStatement(connection, SHOW_TABLES);        
            DatabaseMetaData metaData = connection.getMetaData();
            
            resultSet = PreparedStatementUtil.executeQuery(ps);
            while (ResultSetUtil.next(resultSet)) {
                String tableName = resultSet.getString(tableNameColumn);
                logger.info(String.format("%sから情報を取得しました。", tableName));
                Table table = metaProcess(metaData, schema, tableName);
                metaMap.put(tableName, new ModelMeta(table));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            ResultSetUtil.close(resultSet);
            StatementUtil.close(ps);
        }
        
        for (Entry<String, ModelMeta> entry : metaMap.entrySet()) {
            Table table = entry.getValue().getTable();
            for (Column column : table.getColumnList()) {
                String referencedTable = column.getReferenceTableName();
                if (StringUtil.isNotBlank(referencedTable) && metaMap.containsKey(referencedTable)) {
                    logger.info(String.format("%s.%sから%sへの関連を検出しました。", table.getName(), column.getColumnName(), referencedTable));
                    column.setReferencedModel(metaMap.get(referencedTable.trim()));
                }
            }
        }
        
        // generate code
        String rootPath = String.format("%s/%s", genDir, rootPackage.replace(".", "/"));
        String entityPath = String.format("%s/%s", rootPath, "entity");
        String servicePath = String.format("%s/%s", rootPath, "service");
        FileUtil.mkdirsIfNotExists(rootPath);
        FileUtil.mkdirsIfNotExists(entityPath);
        FileUtil.mkdirsIfNotExists(servicePath);
        
        String entityPackage = String.format("%s.entity", rootPackage);
        String servicePackage = String.format("%s.service", rootPackage);
        
        
        String abstractS2ServiceName = "EnhancedS2AbstractService";
        String parentServicePath = String.format("%s/%s.java", servicePath, abstractS2ServiceName);
        File parentServiceFile = new File(parentServicePath);
        Map<String, Object> parentParams = new HashMap<String, Object>();
        parentParams.put("entityPackage", entityPackage);
        parentParams.put("servicePackage", servicePackage);
        parentParams.put("delFlag", delFlag);
        parentParams.put("abstractS2ServiceName", abstractS2ServiceName);
        writeContentsToFile(parentServiceFile, "template/parent_service.vm", parentParams, true);
        logger.info(String.format("%sを生成しました。", parentServicePath));
        
        // Editable
        String editableServiceName = "EditableS2Service";
        String editableServicePath = String.format("%s/%s.java", servicePath, editableServiceName);
        File editableServiceFile = new File(editableServicePath);
        Map<String, Object> ediPatablerams = new HashMap<String, Object>();
        ediPatablerams.put("servicePackage", servicePackage);
        ediPatablerams.put("abstractS2ServiceName", abstractS2ServiceName);
        ediPatablerams.put("editableServiceName", editableServiceName);
        writeContentsToFile(editableServiceFile, "template/editable_service.vm", ediPatablerams, false);
        
        
        List<ModelMeta> modelMetaItems = new ArrayList<ModelMeta>();
        
        for (Entry<String, ModelMeta> entry : metaMap.entrySet()) {
            ModelMeta modelMeta = entry.getValue();
            
            String abstractEntityFilePath = String.format("%s/%s.java", entityPath, modelMeta.getAbstractEntityName());
            String entityFilePath = String.format("%s/%s.java", entityPath, modelMeta.getEntityName());
            String entityNamesFilePath = String.format("%s/%s.java", entityPath, modelMeta.getNamesName());
            String abstractServiceFilePath = String.format("%s/%s.java", servicePath, modelMeta.getAbstractServiceName());
            String serviceFilePath = String.format("%s/%s.java", servicePath, modelMeta.getServiceName());
            
            File abstractEntityFile = new File(abstractEntityFilePath);
            File entityFile = new File(entityFilePath);
            File entityNamesFile = new File(entityNamesFilePath);
            File abstractServiceFile = new File(abstractServiceFilePath);
            File serviceFile = new File(serviceFilePath);
            
            Map<String, Object> params = new HashMap<String, Object>();
            params.put("entityPackage", entityPackage);
            params.put("servicePackage", servicePackage);
            params.put("meta", modelMeta);
            params.put("delFlag", delFlag);
            params.put("abstractS2ServiceName", abstractS2ServiceName);
            params.put("editableServiceName", editableServiceName);
            
            logger.info(String.format("*%sに関するファイルを生成します。", modelMeta.getTable().getName()));
            writeContentsToFile(abstractEntityFile, "template/abstract_entity.vm", params, true);
            writeContentsToFile(entityFile, "template/entity.vm", params, false);
            writeContentsToFile(entityNamesFile, "template/entity_names.vm", params, true);
            writeContentsToFile(abstractServiceFile, "template/abstract_service.vm", params, true);
            writeContentsToFile(serviceFile, "template/service.vm", params, false);
            
            modelMetaItems.add(modelMeta);
        }
        
        logger.info("Names.javaを生成します。");
        String namesFilePath = String.format("%s/Names.java", entityPath);
        File namesFile = new File(namesFilePath);
        Map<String, Object> namesParams = new HashMap<String, Object>();
        namesParams.put("entityPackage", entityPackage);
        namesParams.put("modelMetaItems", modelMetaItems);
        writeContentsToFile(namesFile, "template/names.vm", namesParams, true);
        
    }
    
    private Table metaProcess(DatabaseMetaData metaData, String schema, String tableName) throws Exception {
        try {
            Set<String> primaryKeySet = getPrimaryKeySet(metaData, schema, tableName);
            ColumnListBuilder columnListBuilder = new ColumnListBuilder(metaData.getColumns(null, schema, tableName, "%"), primaryKeySet);
            List<Column> columnList = columnListBuilder.build();

            ResultSet rs = metaData.getIndexInfo("", schema, tableName, false, false);
            
            Map<String, Index> indexMap = new LinkedHashMap<String, Index>();
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                if (!"PRIMARY".equals(indexName)) {
                    boolean unique = !rs.getBoolean("NON_UNIQUE");
                    Index index = indexMap.containsKey(indexName) ? indexMap.get(indexName) : new Index(indexName, unique);
                    final String columnName = rs.getString("COLUMN_NAME");
                    Collection<Column> target = Collections2.filter(columnList, new Predicate<Column>() {
                        @Override
                        public boolean apply(Column input) {
                            return input.getColumnName().equals(columnName);
                        }
                    });
                    if (!target.isEmpty()) {
                        index.addColumn(target.iterator().next(), rs.getInt("ORDINAL_POSITION"), rs.getString("ASC_OR_DESC").equals("A"));    
                    }
                    
                    indexMap.put(indexName, index);
                }
            }
            rs.close();             

            return new Table(tableName, "", columnList, indexMap);
        } catch (Exception e) {
            throw e;
        }
    }
    
    private Set<String> getPrimaryKeySet(DatabaseMetaData metaData, String schema, String tableName) throws Exception {
        Set<String> columnSet = new LinkedHashSet<String>();
        ResultSet rs = metaData.getPrimaryKeys(null, schema, tableName);
        while (rs.next()) {
            columnSet.add(rs.getString("COLUMN_NAME"));
        }
        rs.close();
        return columnSet;
    }
    
    private void writeContentsToFile(File file, String template, Map<String, Object> params, boolean overwrite) {
        if (overwrite || !file.exists()) {
            String entityContents = VelocityUtil.merge(template, params);
            FileUtil.writeStringToFile(file, entityContents, "UTF-8");
            logger.info(String.format(" |-%sを生成しました。", file.getName()));
        } else {
            logger.info(String.format(" |-%sは既に存在するので自動生成がスキップされました。", file.getName()));
        }
    }
    
    public void setDelFlag(DelFlag delFlag) {
        this.delFlag = delFlag;
    }

    /**
     * @param args
     */
    public static void main(String[] args) {
        GenerateCodeExecutor executor = new GenerateCodeExecutor("/develop/workspace_java/advanced-s2jdbc-gen/target/gen", "me.stormcat.sample", "localhost:3306", "sample", "root", "root");
        DelFlag delFlag = new DelFlag();
        delFlag.setName("valid");
        delFlag.setDelValue(false);
        executor.setDelFlag(delFlag);
        executor.execute();
    }

}
