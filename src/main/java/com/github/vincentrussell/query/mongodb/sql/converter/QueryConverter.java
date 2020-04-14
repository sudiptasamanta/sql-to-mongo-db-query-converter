package com.github.vincentrussell.query.mongodb.sql.converter;

import com.github.vincentrussell.query.mongodb.sql.converter.holder.ExpressionHolder;
import com.github.vincentrussell.query.mongodb.sql.converter.holder.TablesHolder;
import com.github.vincentrussell.query.mongodb.sql.converter.processor.JoinProcessor;
import com.github.vincentrussell.query.mongodb.sql.converter.visitor.ExpVisitorEraseAliasTableBaseBuilder;
import com.github.vincentrussell.query.mongodb.sql.converter.visitor.WhereVisitorMatchAndLookupPipelineMatchBuilder;
import com.github.vincentrussell.query.mongodb.sql.converter.util.SqlUtils;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mongodb.bulk.DeleteRequest;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.result.DeleteResult;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.parser.CCJSqlParser;
import net.sf.jsqlparser.parser.StreamProvider;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.mutable.MutableBoolean;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static org.apache.commons.lang.StringUtils.isEmpty;

public class QueryConverter {

    public static final String D_AGGREGATION_ALLOW_DISK_USE = "aggregationAllowDiskUse";
    public static final String D_AGGREGATION_BATCH_SIZE = "aggregationBatchSize";
    private final MongoDBQueryHolder mongoDBQueryHolder;

    private final Map<String,FieldType> fieldNameToFieldTypeMapping;
    private final FieldType defaultFieldType;
    private final SQLCommandInfoHolder sqlCommandInfoHolder;

    /**
     * Create a QueryConverter with a string
     * @param sql the sql statement
     * @throws ParseException when the sql query cannot be parsed
     */
    public QueryConverter(String sql) throws ParseException {
        this(new ByteArrayInputStream(sql.getBytes(Charsets.UTF_8)), Collections.<String, FieldType>emptyMap(), FieldType.UNKNOWN);
    }

    /**
     * Create a QueryConverter with a string
     * @param sql the sql statement
     * @param fieldNameToFieldTypeMapping mapping for each field
     * @throws ParseException when the sql query cannot be parsed
     */
    public QueryConverter(String sql, Map<String,FieldType> fieldNameToFieldTypeMapping) throws ParseException {
        this(new ByteArrayInputStream(sql.getBytes(Charsets.UTF_8)),fieldNameToFieldTypeMapping, FieldType.UNKNOWN);
    }

    /**
     * Create a QueryConverter with a string
     * @param sql sql string
     * @param fieldType the default {@link FieldType} to be used
     * @throws ParseException
     */
    public QueryConverter(String sql, FieldType fieldType) throws ParseException {
        this(new ByteArrayInputStream(sql.getBytes(Charsets.UTF_8)), Collections.<String, FieldType>emptyMap(), fieldType);
    }

    /**
     * Create a QueryConverter with a string
     * @param sql sql string
     * @param fieldNameToFieldTypeMapping mapping for each field
     * @param defaultFieldType defaultFieldType the default {@link FieldType} to be used
     * @throws ParseException
     */
    public QueryConverter(String sql, Map<String, FieldType> fieldNameToFieldTypeMapping, FieldType defaultFieldType) throws ParseException {
        this(new ByteArrayInputStream(sql.getBytes(Charsets.UTF_8)), fieldNameToFieldTypeMapping, defaultFieldType);
    }

    /**
     * Create a QueryConverter with a string
     * @param inputStream an input stream that has the sql statement in it
     * @throws ParseException when the sql query cannot be parsed
     */
    public QueryConverter(InputStream inputStream) throws ParseException {
        this(inputStream,Collections.<String, FieldType>emptyMap(), FieldType.UNKNOWN);
    }

    /**
     * Create a QueryConverter with an InputStream
     * @param inputStream an input stream that has the sql statement in it
     * @param fieldNameToFieldTypeMapping mapping for each field
     * @param defaultFieldType the default {@link FieldType} to be used
     * @throws ParseException when the sql query cannot be parsed
     */
    public QueryConverter(InputStream inputStream, Map<String,FieldType> fieldNameToFieldTypeMapping,
                          FieldType defaultFieldType) throws ParseException {
        try {
            StreamProvider streamProvider = new StreamProvider(inputStream, Charsets.UTF_8.name());
            CCJSqlParser jSqlParser = new CCJSqlParser(streamProvider);
            this.defaultFieldType = defaultFieldType != null ? defaultFieldType : FieldType.UNKNOWN;
            this.sqlCommandInfoHolder = SQLCommandInfoHolder.Builder
                    .create(defaultFieldType, fieldNameToFieldTypeMapping)
                    .setJSqlParser(jSqlParser)
                    .build();
            this.fieldNameToFieldTypeMapping = fieldNameToFieldTypeMapping != null
                    ? fieldNameToFieldTypeMapping : Collections.<String, FieldType>emptyMap();

            net.sf.jsqlparser.parser.Token nextToken = jSqlParser.getNextToken();
            SqlUtils.isTrue(isEmpty(nextToken.image) || ";".equals(nextToken.image), "unable to parse complete sql string. one reason for this is the use of double equals (==)");

            mongoDBQueryHolder = getMongoQueryInternal();
            validate();
        } catch (IOException e) {
            throw new ParseException(e.getMessage());
        } catch (net.sf.jsqlparser.parser.ParseException e) {
            throw SqlUtils.convertParseException(e);
        }
    }

    private void validate() throws ParseException {
        List<SelectItem> selectItems = sqlCommandInfoHolder.getSelectItems();
        List<SelectItem> filteredItems = Lists.newArrayList(Iterables.filter(selectItems, new Predicate<SelectItem>() {
            @Override
            public boolean apply(SelectItem selectItem) {
                try {
                    if (SelectExpressionItem.class.isInstance(selectItem)
                            && (Column.class.isInstance(((SelectExpressionItem) selectItem).getExpression())
                            || CaseExpression.class.isInstance(((SelectExpressionItem) selectItem).getExpression())
                            || Subtraction.class.isInstance(((SelectExpressionItem) selectItem).getExpression()))) {
                            return true;
                    }
                } catch (NullPointerException e) {
                    return false;
                }
                return false;
            }
        }));

        SqlUtils.isFalse((selectItems.size() >1
                || SqlUtils.isSelectAll(selectItems))
                && sqlCommandInfoHolder.isDistinct(),"cannot run distinct one more than one column");
        SqlUtils.isFalse(sqlCommandInfoHolder.getGoupBys().size() == 0 && selectItems.size()!=filteredItems.size() && !SqlUtils.isSelectAll(selectItems)
                && !SqlUtils.isCountAll(selectItems),"illegal expression(s) found in select clause.  Only column names supported");
    }

    /**
     * get the object that you need to submit a query
     * @return the {@link com.github.vincentrussell.query.mongodb.sql.converter.MongoDBQueryHolder}
     * that contains all that is needed to describe the query to be run.
     */
    public MongoDBQueryHolder getMongoQuery() {
        return mongoDBQueryHolder;
    }

    private MongoDBQueryHolder getMongoQueryInternal() throws ParseException {
        MongoDBQueryHolder mongoDBQueryHolder = new MongoDBQueryHolder(sqlCommandInfoHolder.getTable(), sqlCommandInfoHolder.getSqlCommandType());
        Document document = new Document();
        if (sqlCommandInfoHolder.isDistinct()) {
            document.put(sqlCommandInfoHolder.getSelectItems().get(0).toString(), 1);
            mongoDBQueryHolder.setProjection(document);
            mongoDBQueryHolder.setDistinct(sqlCommandInfoHolder.isDistinct());
        } else if (sqlCommandInfoHolder.getGoupBys().size() > 0) {
        	List<String> groupBys = preprocessGroupBy(sqlCommandInfoHolder.getGoupBys(),sqlCommandInfoHolder.getTablesHolder());
        	List<SelectItem> selects = preprocessSelect(sqlCommandInfoHolder.getSelectItems(),sqlCommandInfoHolder.getTablesHolder());
        	if(sqlCommandInfoHolder.getGoupBys().size() > 0) {
        		mongoDBQueryHolder.setGroupBys(groupBys);
        	}
            mongoDBQueryHolder.setProjection(createProjectionsFromSelectItems(selects, groupBys));
            mongoDBQueryHolder.setAliasProjection(createAliasProjectionForGroupItems(selects, groupBys));
        } else if (sqlCommandInfoHolder.isCountAll()) {
            mongoDBQueryHolder.setCountAll(sqlCommandInfoHolder.isCountAll());
        } else if (!SqlUtils.isSelectAll(sqlCommandInfoHolder.getSelectItems())) {
            document.put("_id",0);
            for (SelectItem selectItem : sqlCommandInfoHolder.getSelectItems()) {
            	SelectExpressionItem selectExpressionItem =  ((SelectExpressionItem) selectItem);
            	if(selectExpressionItem.getExpression() instanceof Column) {
            		Column c = (Column)selectExpressionItem.getExpression();
            		//If we found alias of base table we ignore it because basetable doesn't need alias, it's itself
            		String columnName = SqlUtils.removeAliasFromColumn(c, sqlCommandInfoHolder.getTablesHolder().getBaseAliasTable()).getColumnName();
                    Alias alias = selectExpressionItem.getAlias();
                    document.put((alias != null ? alias.getName() : columnName ),(alias != null ? "$" + columnName : 1 ));
            	} else if(CaseExpression.class.isInstance(selectExpressionItem.getExpression())) {
                    Document swithStatement = SqlUtils.getSwitchStatement(selectExpressionItem.getExpression());
                    document.put(selectExpressionItem.getAlias().getName().replaceAll("\\.", "_"),swithStatement);
                } else if(selectExpressionItem.getExpression() instanceof Subtraction){
            	    Subtraction subtraction = (Subtraction)selectExpressionItem.getExpression();
            	    Object subtractionDoc = SqlUtils.getSubtractionDocument(subtraction);
            	    document.put(selectExpressionItem.getAlias().getName(),subtractionDoc);
                }
            	else if (selectExpressionItem.getExpression() instanceof SubSelect){
            		throw new ParseException("Unsupported subselect expression");
            	}
            	else {
            		throw new ParseException("Unsupported project expression");
            	}
            }
            mongoDBQueryHolder.setProjection(document);
        }
        
        if (sqlCommandInfoHolder.getJoins() != null) {
        	mongoDBQueryHolder.setJoinPipeline(JoinProcessor.toPipelineSteps(sqlCommandInfoHolder.getTablesHolder(), sqlCommandInfoHolder.getJoins(), sqlCommandInfoHolder.getWhereClause()));
        }

        if (sqlCommandInfoHolder.getOrderByElements()!=null && sqlCommandInfoHolder.getOrderByElements().size() > 0) {
            mongoDBQueryHolder.setSort(createSortInfoFromOrderByElements(preprocessOrderBy(sqlCommandInfoHolder.getOrderByElements(),sqlCommandInfoHolder.getTablesHolder()),sqlCommandInfoHolder.getAliasHash(),sqlCommandInfoHolder.getGoupBys()));
        }

        if (sqlCommandInfoHolder.getWhereClause()!=null) {
            WhereCauseProcessor whereCauseProcessor = new WhereCauseProcessor(defaultFieldType,
                    fieldNameToFieldTypeMapping);
            Expression preprocessedWhere = preprocessWhere(sqlCommandInfoHolder.getWhereClause(), sqlCommandInfoHolder.getTablesHolder());
            if(preprocessedWhere != null) {//can't be null because of where of joined tables
            	mongoDBQueryHolder.setQuery((Document) whereCauseProcessor
                    .parseExpression(new Document(), preprocessedWhere , null));
            }
        }
        mongoDBQueryHolder.setOffset(sqlCommandInfoHolder.getOffset());
        mongoDBQueryHolder.setLimit(sqlCommandInfoHolder.getLimit());
        
        return mongoDBQueryHolder;
    }
    
    //Erase table base alias and get where part of main table when joins
    private Expression preprocessWhere(Expression exp, TablesHolder tholder){
    	if(sqlCommandInfoHolder.getJoins()!=null && !sqlCommandInfoHolder.getJoins().isEmpty()) {
    		ExpressionHolder partialWhereExpHolder = new ExpressionHolder(null);
    		MutableBoolean haveOrExpression = new MutableBoolean(false);
			exp.accept(new WhereVisitorMatchAndLookupPipelineMatchBuilder(tholder.getBaseAliasTable(), partialWhereExpHolder, haveOrExpression));
			if(haveOrExpression.booleanValue()) {
				return null;//with or exp we can't use match first step
			}
			exp = partialWhereExpHolder.getExpression();
        }
    	if(exp != null) {
    		exp.accept(new ExpVisitorEraseAliasTableBaseBuilder(tholder.getBaseAliasTable()));
    	}
    	return exp;
    }
    
    //Erase table base alias
    private List<OrderByElement> preprocessOrderBy(List<OrderByElement> lord, TablesHolder tholder){
    	for(OrderByElement ord : lord) {
    		ord.getExpression().accept(new ExpVisitorEraseAliasTableBaseBuilder(tholder.getBaseAliasTable()));
    	}
    	return lord;
    }
    
    //Erase table base alias
    private List<SelectItem> preprocessSelect(List<SelectItem> lsel, TablesHolder tholder){
    	for(SelectItem sel : lsel) {
    		sel.accept(new ExpVisitorEraseAliasTableBaseBuilder(tholder.getBaseAliasTable()));
    	}
    	return lsel;
    }
    
  //Erase table base alias
    private List<String> preprocessGroupBy(List<String> lgroup, TablesHolder tholder){
    	List<String> lgroupEraseAlias = new LinkedList<String>();
    	for(String group: lgroup) {
    		int index = group.indexOf(tholder.getBaseAliasTable()  + ".");
    		if(index != -1) {
    			lgroupEraseAlias.add(group.substring(tholder.getBaseAliasTable().length()+1));
    		}
    		else {
    			lgroupEraseAlias.add(group);
    		}
    	}
    	return lgroupEraseAlias;
    }

    private Document createSortInfoFromOrderByElements(List<OrderByElement> orderByElements, HashMap<String,String> aliasHash, List<String> groupBys) throws ParseException {
        Document document = new Document();
        if (orderByElements==null && orderByElements.size()==0) {
            return document;
        }

        final List<OrderByElement> functionItems = Lists.newArrayList(Iterables.filter(orderByElements, new Predicate<OrderByElement>() {
            @Override
            public boolean apply(OrderByElement orderByElement) {
                try {
                    if (Function.class.isInstance(orderByElement.getExpression())) {
                        return true;
                    }
                } catch (NullPointerException e) {
                    return false;
                }
                return false;
            }
        }));
        final List<OrderByElement> nonFunctionItems = Lists.newArrayList(Collections2.filter(orderByElements, new Predicate<OrderByElement>() {
            @Override
            public boolean apply(OrderByElement orderByElement) {
                return !functionItems.contains(orderByElement);
            }

        }));

        Document sortItems = new Document();
        for (OrderByElement orderByElement : orderByElements) {
            if (nonFunctionItems.contains(orderByElement)) {
            	String sortField = SqlUtils.getStringValue(orderByElement.getExpression());
            	if(!groupBys.isEmpty()) {
            		if(groupBys.size() > 1) {
            			sortField = "_id." + sortField.replaceAll("\\.", "_");
            		}
            		else {
            			sortField = "_id";
            		}
            	}
                sortItems.put(sortField, orderByElement.isAsc() ? 1 : -1);
            } else {
                Function function = (Function) orderByElement.getExpression();
                String sortKey;
                String alias = aliasHash.get(function.toString());
                if(alias!=null && !alias.equals(function.toString())) {
                	sortKey = alias;
                }
                else {
	                Document parseFunctionDocument = new Document();
	                parseFunctionForAggregation(function,parseFunctionDocument,Collections.<String>emptyList(),null);
	                sortKey = Iterables.get(parseFunctionDocument.keySet(),0);
                }
                sortItems.put(sortKey,orderByElement.isAsc() ? 1 : -1);
            }
        }

        return sortItems;
    }

    private Document createProjectionsFromSelectItems(List<SelectItem> selectItems, List<String> groupBys) throws ParseException {
        Document document = new Document();
        if (selectItems==null && selectItems.size()==0) {
            return document;
        }

        final List<SelectItem> functionItems = Lists.newArrayList(Iterables.filter(selectItems, new Predicate<SelectItem>() {
            @Override
            public boolean apply(SelectItem selectItem) {
                try {
                    if (SelectExpressionItem.class.isInstance(selectItem)
                            && Function.class.isInstance(((SelectExpressionItem) selectItem).getExpression())) {
                        return true;
                    }
                } catch (NullPointerException e) {
                    return false;
                }
                return false;
            }
        }));
        final List<SelectItem> nonFunctionItems = Lists.newArrayList(Collections2.filter(selectItems, new Predicate<SelectItem>() {
            @Override
            public boolean apply(SelectItem selectItem) {
                return !functionItems.contains(selectItem);
            }

        }));

        Document idDocument = new Document();
        for (SelectItem selectItem : nonFunctionItems) {
        	SelectExpressionItem selectExpressionItem =  ((SelectExpressionItem) selectItem);
            if(CaseExpression.class.isInstance(selectExpressionItem.getExpression())) {
                String columnName = SqlUtils.getGroupByColName(selectExpressionItem.getAlias(),   groupBys);
                if(columnName != null){
                    idDocument.put(columnName, "$" + columnName);
                }
            } else {
                Column column = (Column) selectExpressionItem.getExpression();
                String columnName = SqlUtils.getStringValue(column);
                idDocument.put(columnName.replaceAll("\\.", "_"), "$" + columnName);
            }
        }

        for (String grpKey:groupBys
             ) {
            String projectKey = grpKey.replaceAll("\\.","_");
            if(idDocument.get(grpKey) == null && idDocument.get(projectKey)==null){
                idDocument.put(grpKey,"$" + grpKey);
            } else if(idDocument.get(grpKey) == null && idDocument.get(projectKey)!=null){
                idDocument.put(projectKey,"$" + grpKey);
            }
        }

        document.append("_id", idDocument.size() == 1 ? Iterables.get(idDocument.values(),0) : idDocument);

        for (SelectItem selectItem : functionItems) {
            Function function = (Function) ((SelectExpressionItem)selectItem).getExpression();
            parseFunctionForAggregation(function,document,groupBys,((SelectExpressionItem)selectItem).getAlias());
        }

        return document;
    }

    private Document createAliasProjectionForGroupItems(List<SelectItem> selectItems, List<String> groupBys) throws ParseException {
    	Document document = new Document();
    	
    	final List<SelectItem> functionItems = Lists.newArrayList(Iterables.filter(selectItems, new Predicate<SelectItem>() {
            @Override
            public boolean apply(SelectItem selectItem) {
                try {
                    if (SelectExpressionItem.class.isInstance(selectItem)
                            && Function.class.isInstance(((SelectExpressionItem) selectItem).getExpression())) {
                        return true;
                    }
                } catch (NullPointerException e) {
                    return false;
                }
                return false;
            }
        }));
        final List<SelectItem> nonFunctionItems = Lists.newArrayList(Collections2.filter(selectItems, new Predicate<SelectItem>() {
            @Override
            public boolean apply(SelectItem selectItem) {
                return !functionItems.contains(selectItem);
            }

        }));
        
        if(nonFunctionItems.size() == 1) {
        	SelectExpressionItem selectExpressionItem =  ((SelectExpressionItem) nonFunctionItems.get(0));
        	Column column = (Column) selectExpressionItem.getExpression();
            String columnName = SqlUtils.getStringValue(column);
            Alias alias = selectExpressionItem.getAlias();
            String nameOrAlias = (alias != null ? alias.getName() : columnName);
        	document.put(nameOrAlias, "$_id");
        }
        else {
	        for (SelectItem selectItem : nonFunctionItems) {
	        	SelectExpressionItem selectExpressionItem =  ((SelectExpressionItem) selectItem);
                Alias alias = selectExpressionItem.getAlias();
                if(CaseExpression.class.isInstance(selectExpressionItem.getExpression())) {
                    Document swithStatement = SqlUtils.getSwitchStatement(selectExpressionItem.getExpression());
                    document.put(selectExpressionItem.getAlias().getName().replaceAll("\\.", "_"),swithStatement);
                } else {
                    Column column = (Column) selectExpressionItem.getExpression();
                    String columnName = SqlUtils.getStringValue(column);
                    String nameOrAlias = (alias != null ? alias.getName() : columnName);
                    document.put(nameOrAlias, "$_id." + columnName.replaceAll("\\.", "_"));
                }
	        }
        }
        
        for (SelectItem selectItem : functionItems) {
        	SelectExpressionItem selectExpressionItem =  ((SelectExpressionItem) selectItem);
        	String columnName = ((Function) selectExpressionItem.getExpression()).getName().toLowerCase();
            Alias alias = selectExpressionItem.getAlias();
            String nameOrAlias = (alias != null ? alias.getName() : columnName);
        	document.put(nameOrAlias, 1);
        }
        
        document.put("_id", 0);
    	
    	return document;
    }
    
    private void parseFunctionForAggregation(Function function, Document document, List<String> groupBys, Alias alias) throws ParseException {
        List<String> parameters = function.getParameters()== null ? Collections.<String>emptyList() : Lists.transform(function.getParameters().getExpressions(), new com.google.common.base.Function<Expression, String>() {
            @Override
            public String apply(Expression expression) {
                return SqlUtils.getStringValue(expression);
            }
        });
        if (parameters.size() > 1) {
            throw new ParseException(function.getName()+" function can only have one parameter");
        }
        String field = parameters.size() > 0 ? Iterables.get(parameters, 0).replaceAll("\\.","_") : null;
        if ("sum".equals(function.getName().toLowerCase())) {
            createFunction( alias,"sum",field, document,"$"+ field);
        } else if ("avg".equals(function.getName().toLowerCase())) {
            createFunction(alias,(alias == null?"avg":alias.getName()),field, document,"$"+ field);
        } else if ("count".equals(function.getName().toLowerCase())) {
            document.put((alias == null?"count":alias.getName()),new Document("$sum",1));
        } else if ("min".equals(function.getName().toLowerCase())) {
            createFunction(alias,"min",field, document,"$"+ field);
        } else if ("max".equals(function.getName().toLowerCase())) {
            createFunction(alias,"max",field, document,"$"+ field);
        } else {
            throw new ParseException("could not understand function:" + function.getName());
        }
    }

    private void createFunction(Alias alias, String functionName, String field, Document document, Object value) throws ParseException {
        String keyName = alias == null?functionName + "_"+field:alias.getName();
        SqlUtils.isTrue(field!=null,"function "+ functionName + " must contain a single field to run on");
        document.put(keyName,new Document("$"+functionName,value));
    }


    /**
     * Build a mongo shell statement with the code to run the specified query.
     * @param outputStream the {@link java.io.OutputStream} to write the data to
     * @throws IOException when there is an issue writing to the {@link java.io.OutputStream}
     */
    public void write(OutputStream outputStream) throws IOException {
        MongoDBQueryHolder mongoDBQueryHolder = getMongoQuery();
        if (mongoDBQueryHolder.isDistinct()) {
            IOUtils.write("db." + mongoDBQueryHolder.getCollection() + ".distinct(", outputStream);
            IOUtils.write("\""+getDistinctFieldName(mongoDBQueryHolder) + "\"", outputStream);
            IOUtils.write(" , ", outputStream);
            IOUtils.write(prettyPrintJson(mongoDBQueryHolder.getQuery().toJson()), outputStream);
        } else if (!sqlCommandInfoHolder.getAliasHash().isEmpty() || sqlCommandInfoHolder.getGoupBys().size() > 0 || (sqlCommandInfoHolder.getJoins() != null && sqlCommandInfoHolder.getJoins().size() > 0)) {
            IOUtils.write("db." + mongoDBQueryHolder.getCollection() + ".aggregate(", outputStream);
            IOUtils.write("[", outputStream);
            List<Document> documents = new ArrayList<>();
            documents.add(new Document("$match",mongoDBQueryHolder.getQuery()));
            
            if(sqlCommandInfoHolder.getJoins() != null && !sqlCommandInfoHolder.getJoins().isEmpty()) {
            	documents.addAll(mongoDBQueryHolder.getJoinPipeline());
            }
            
            if(!sqlCommandInfoHolder.getGoupBys().isEmpty()) {
            	documents.add(new Document("$group",mongoDBQueryHolder.getProjection()));
            }
            
            if (mongoDBQueryHolder.getSort()!=null && mongoDBQueryHolder.getSort().size() > 0) {
                documents.add(new Document("$sort",mongoDBQueryHolder.getSort()));
            }
            
            if (mongoDBQueryHolder.getOffset()!= -1) {
                documents.add(new Document("$skip",mongoDBQueryHolder.getOffset()));
            }

            if (mongoDBQueryHolder.getLimit()!= -1) {
                documents.add(new Document("$limit",mongoDBQueryHolder.getLimit()));
            }
            
            Document aliasProjection = mongoDBQueryHolder.getAliasProjection();
            if(!aliasProjection.isEmpty()) {
            	documents.add(new Document("$project",aliasProjection));
            }
            
            if(sqlCommandInfoHolder.getGoupBys().isEmpty()) {//Alias no group
            	Document projection = mongoDBQueryHolder.getProjection();
            	documents.add(new Document("$project",projection));
            }

            IOUtils.write(Joiner.on(",").join(Lists.transform(documents, new com.google.common.base.Function<Document, String>() {
                @Override
                public String apply(Document document) {
                    return prettyPrintJson(document.toJson());
                }
            })),outputStream);
            IOUtils.write("]", outputStream);

            Document options = new Document();
            if (System.getProperty(D_AGGREGATION_ALLOW_DISK_USE)!=null) {
                options.put("allowDiskUse",Boolean.valueOf(System.getProperty(D_AGGREGATION_ALLOW_DISK_USE)));
            }

            if (System.getProperty(D_AGGREGATION_BATCH_SIZE)!=null) {
                options.put("cursor",new Document("batchSize",Integer.valueOf(System.getProperty(D_AGGREGATION_BATCH_SIZE))));
            }

            if (options.size() > 0) {
                IOUtils.write(",",outputStream);
                IOUtils.write(prettyPrintJson(options.toJson()),outputStream);
            }



        } else if (sqlCommandInfoHolder.isCountAll()) {
            IOUtils.write("db." + mongoDBQueryHolder.getCollection() + ".count(", outputStream);
            IOUtils.write(prettyPrintJson(mongoDBQueryHolder.getQuery().toJson()), outputStream);
        } else {
            IOUtils.write("db." + mongoDBQueryHolder.getCollection() + ".find(", outputStream);
            IOUtils.write(prettyPrintJson(mongoDBQueryHolder.getQuery().toJson()), outputStream);
            if (mongoDBQueryHolder.getProjection() != null && mongoDBQueryHolder.getProjection().size() > 0) {
                IOUtils.write(" , ", outputStream);
                IOUtils.write(prettyPrintJson(mongoDBQueryHolder.getProjection().toJson()), outputStream);
            }
        }
        IOUtils.write(")", outputStream);

        if (mongoDBQueryHolder.getSort()!=null && mongoDBQueryHolder.getSort().size() > 0
                && !sqlCommandInfoHolder.isCountAll() && !sqlCommandInfoHolder.isDistinct() && sqlCommandInfoHolder.getGoupBys().isEmpty() && sqlCommandInfoHolder.getAliasHash().isEmpty()) {
            IOUtils.write(".sort(", outputStream);
            IOUtils.write(prettyPrintJson(mongoDBQueryHolder.getSort().toJson()), outputStream);
            IOUtils.write(")", outputStream);
        }
        
        if (mongoDBQueryHolder.getOffset()!=-1
                && !sqlCommandInfoHolder.isCountAll() && !sqlCommandInfoHolder.isDistinct()
                && sqlCommandInfoHolder.getGoupBys().isEmpty() && sqlCommandInfoHolder.getAliasHash().isEmpty()) {
            IOUtils.write(".skip(", outputStream);
            IOUtils.write(mongoDBQueryHolder.getOffset()+"", outputStream);
            IOUtils.write(")", outputStream);
        }

        if (mongoDBQueryHolder.getLimit()!=-1
                && !sqlCommandInfoHolder.isCountAll() && !sqlCommandInfoHolder.isDistinct()
                && sqlCommandInfoHolder.getGoupBys().isEmpty() && sqlCommandInfoHolder.getAliasHash().isEmpty()) {
            IOUtils.write(".limit(", outputStream);
            IOUtils.write(mongoDBQueryHolder.getLimit()+"", outputStream);
            IOUtils.write(")", outputStream);
        }
    }

    private String getDistinctFieldName(MongoDBQueryHolder mongoDBQueryHolder) {
        return Iterables.get(mongoDBQueryHolder.getProjection().keySet(),0);
    }

    /**
     * @param mongoDatabase the database to run the query against.
     * @param <T> variable based on the type of query run.
     * @return When query does a find will return QueryResultIterator&lt;{@link org.bson.Document}&gt;
     *           When query does a count will return a Long
     *           When query does a distinct will return QueryResultIterator&lt;{@link java.lang.String}&gt;
     */
    @SuppressWarnings("unchecked")
    public <T> T run(MongoDatabase mongoDatabase) {
        MongoDBQueryHolder mongoDBQueryHolder = getMongoQuery();

        MongoCollection mongoCollection = mongoDatabase.getCollection(mongoDBQueryHolder.getCollection());

        if (SQLCommandType.SELECT.equals(mongoDBQueryHolder.getSqlCommandType())) {
            if (mongoDBQueryHolder.isDistinct()) {
                return (T) new QueryResultIterator<>(mongoCollection.distinct(getDistinctFieldName(mongoDBQueryHolder), mongoDBQueryHolder.getQuery(), String.class));
            } else if (mongoDBQueryHolder.isCountAll()) {
                return (T) Long.valueOf(mongoCollection.count(mongoDBQueryHolder.getQuery()));
            } else if (!sqlCommandInfoHolder.getAliasHash().isEmpty() || sqlCommandInfoHolder.getGoupBys().size() > 0 || (sqlCommandInfoHolder.getJoins() != null && !sqlCommandInfoHolder.getJoins().isEmpty())) {
                List<Document> documents = new ArrayList<>();
                if (mongoDBQueryHolder.getQuery() != null && mongoDBQueryHolder.getQuery().size() > 0) {
                    documents.add(new Document("$match", mongoDBQueryHolder.getQuery()));
                }
                if(sqlCommandInfoHolder.getJoins() != null && !sqlCommandInfoHolder.getJoins().isEmpty()) {
                	documents.addAll(mongoDBQueryHolder.getJoinPipeline());
                }
                if(!sqlCommandInfoHolder.getGoupBys().isEmpty()) {
                	documents.add(new Document("$group", mongoDBQueryHolder.getProjection()));
                }
                if (mongoDBQueryHolder.getSort() != null && mongoDBQueryHolder.getSort().size() > 0) {
                    documents.add(new Document("$sort", mongoDBQueryHolder.getSort()));
                }
                if (mongoDBQueryHolder.getOffset() != -1) {
                    documents.add(new Document("$skip", mongoDBQueryHolder.getOffset()));
                }
                if (mongoDBQueryHolder.getLimit() != -1) {
                    documents.add(new Document("$limit", mongoDBQueryHolder.getLimit()));
                }
                
                Document aliasProjection = mongoDBQueryHolder.getAliasProjection();
                if(!aliasProjection.isEmpty()) {//Alias Group by
                	documents.add(new Document("$project",aliasProjection));
                }
                
                if(sqlCommandInfoHolder.getGoupBys().isEmpty()) {//Alias no group
                	Document projection = mongoDBQueryHolder.getProjection();
                	documents.add(new Document("$project",projection));
                }
                
                AggregateIterable aggregate = mongoCollection.aggregate(documents);

                if (System.getProperty(D_AGGREGATION_ALLOW_DISK_USE) != null) {
                    aggregate.allowDiskUse(Boolean.valueOf(System.getProperty(D_AGGREGATION_ALLOW_DISK_USE)));
                }

                if (System.getProperty(D_AGGREGATION_BATCH_SIZE) != null) {
                    aggregate.batchSize(Integer.valueOf(System.getProperty(D_AGGREGATION_BATCH_SIZE)));
                }

                return (T) new QueryResultIterator<>(aggregate);
            } else {
                FindIterable findIterable = mongoCollection.find(mongoDBQueryHolder.getQuery()).projection(mongoDBQueryHolder.getProjection());
                if (mongoDBQueryHolder.getSort() != null && mongoDBQueryHolder.getSort().size() > 0) {
                    findIterable.sort(mongoDBQueryHolder.getSort());
                }
                if (mongoDBQueryHolder.getOffset() != -1) {
                    findIterable.skip((int) mongoDBQueryHolder.getOffset());
                }
                if (mongoDBQueryHolder.getLimit() != -1) {
                    findIterable.limit((int) mongoDBQueryHolder.getLimit());
                }

                return (T) new QueryResultIterator<>(findIterable);
            }
        } else if (SQLCommandType.DELETE.equals(mongoDBQueryHolder.getSqlCommandType())) {
            DeleteResult deleteResult = mongoCollection.deleteMany(mongoDBQueryHolder.getQuery());
            return (T)((Long)deleteResult.getDeletedCount());
        } else {
            throw new UnsupportedOperationException("SQL command type not supported");
        }
    }
    
    private static String toJson(List<Document> documents) throws IOException {
        StringWriter stringWriter = new StringWriter();
        final JsonWriterSettings jsonWriterSettings = new JsonWriterSettings(JsonMode.STRICT, "\t", "\n");
        IOUtils.write("[", stringWriter);
        IOUtils.write(Joiner.on(",").join(Lists.transform(documents, new com.google.common.base.Function<Document, String>() {
            @Override
            public String apply(Document document) {
                return document.toJson(jsonWriterSettings);
            }
        })),stringWriter);
        IOUtils.write("]", stringWriter);
        return stringWriter.toString();
    }


    private String prettyPrintJson(String json) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        JsonParser jp = new JsonParser();
        JsonElement je = jp.parse(json);
        return gson.toJson(je);
    }


}
