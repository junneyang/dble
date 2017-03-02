package io.mycat.route.parser.druid.impl;

import java.sql.SQLNonTransientException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLOrderingSpecification;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAggregateExpr;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLNumericLiteralExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.expr.SQLTextLiteralExpr;
import com.alibaba.druid.sql.ast.statement.SQLSelectGroupByClause;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectOrderByItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.ast.expr.MySqlOrderingExpr;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlSchemaStatVisitor;

import io.mycat.MycatServer;
import io.mycat.cache.LayerCachePool;
import io.mycat.config.model.SchemaConfig;
import io.mycat.route.RouteResultset;
import io.mycat.route.RouteResultsetNode;
import io.mycat.route.parser.druid.MycatSchemaStatVisitor;
import io.mycat.route.parser.druid.RouteCalculateUnit;
import io.mycat.route.util.RouterUtil;
import io.mycat.sqlengine.mpp.HavingCols;
import io.mycat.sqlengine.mpp.MergeCol;
import io.mycat.sqlengine.mpp.OrderCol;
import io.mycat.util.ObjectUtil;
import io.mycat.util.StringUtil;

public class DruidBaseSelectParser extends DefaultDruidParser {
	protected boolean isNeedParseOrderAgg = true;
	@Override
	public SchemaConfig visitorParse(SchemaConfig schema, RouteResultset rrs, SQLStatement stmt,
			MycatSchemaStatVisitor visitor) throws SQLNonTransientException {
		return super.visitorParse(schema, rrs, stmt, visitor);
	}

	protected void parseOrderAggGroupMysql(SchemaConfig schema, SQLStatement stmt, RouteResultset rrs,
			MySqlSelectQueryBlock mysqlSelectQuery) {
		MySqlSchemaStatVisitor visitor = new MySqlSchemaStatVisitor();
		stmt.accept(visitor);
		if (!isNeedParseOrderAgg) {
			return;
		}
		Map<String, String> aliaColumns = parseAggGroupCommon(schema, stmt, rrs, mysqlSelectQuery);

		// setOrderByCols
		if (mysqlSelectQuery.getOrderBy() != null) {
			List<SQLSelectOrderByItem> orderByItems = mysqlSelectQuery.getOrderBy().getItems();
			rrs.setOrderByCols(buildOrderByCols(orderByItems, aliaColumns));
		}
		isNeedParseOrderAgg = false;
	}

	protected Map<String, String> parseAggGroupCommon(SchemaConfig schema, SQLStatement stmt, RouteResultset rrs,
			SQLSelectQueryBlock mysqlSelectQuery) {
		Map<String, String> aliaColumns = new HashMap<String, String>();
		Map<String, Integer> aggrColumns = new HashMap<String, Integer>();
		// Added by winbill, 20160314, for having clause, Begin ==>
		List<String> havingColsName = new ArrayList<String>();
		// Added by winbill, 20160314, for having clause, End <==
		List<SQLSelectItem> selectList = mysqlSelectQuery.getSelectList();
		boolean isNeedChangeSql = false;
		int size = selectList.size();
		boolean isDistinct = mysqlSelectQuery.getDistionOption() == 2;
		for (int i = 0; i < size; i++) {
			SQLSelectItem item = selectList.get(i);
			if (item.getExpr() instanceof SQLAggregateExpr) {
				SQLAggregateExpr expr = (SQLAggregateExpr) item.getExpr();
				String method = expr.getMethodName();
				boolean isHasArgument = !expr.getArguments().isEmpty();
				if (isHasArgument) {
					String aggrColName = method + "(" + expr.getArguments().get(0) + ")"; // Added
																							// by
																							// winbill,
																							// 20160314,
																							// for
																							// having
																							// clause
					havingColsName.add(aggrColName); // Added by winbill,
														// 20160314, for having
														// clause
				}
				// 只处理有别名的情况，无别名添加别名，否则某些数据库会得不到正确结果处理
				int mergeType = MergeCol.getMergeType(method);
				if (MergeCol.MERGE_AVG == mergeType && isRoutMultiNode(schema, rrs)) { // 跨分片avg需要特殊处理，直接avg结果是不对的
					String colName = item.getAlias() != null ? item.getAlias() : method + i;
					SQLSelectItem sum = new SQLSelectItem();
					String sumColName = colName + "SUM";
					sum.setAlias(sumColName);
					SQLAggregateExpr sumExp = new SQLAggregateExpr("SUM");
					ObjectUtil.copyProperties(expr, sumExp);
					sumExp.getArguments().addAll(expr.getArguments());
					sumExp.setMethodName("SUM");
					sum.setExpr(sumExp);
					selectList.set(i, sum);
					aggrColumns.put(sumColName, MergeCol.MERGE_SUM);
					havingColsName.add(sumColName); // Added by winbill,
													// 20160314, for having
													// clause
					havingColsName.add(item.getAlias() != null ? item.getAlias() : ""); // Added
																						// by
																						// winbill,
																						// 20160314,
																						// two
																						// aliases
																						// for
																						// AVG

					SQLSelectItem count = new SQLSelectItem();
					String countColName = colName + "COUNT";
					count.setAlias(countColName);
					SQLAggregateExpr countExp = new SQLAggregateExpr("COUNT");
					ObjectUtil.copyProperties(expr, countExp);
					countExp.getArguments().addAll(expr.getArguments());
					countExp.setMethodName("COUNT");
					count.setExpr(countExp);
					selectList.add(count);
					aggrColumns.put(countColName, MergeCol.MERGE_COUNT);

					isNeedChangeSql = true;
					aggrColumns.put(colName, mergeType);
					rrs.setHasAggrColumn(true);
				} else if (MergeCol.MERGE_UNSUPPORT != mergeType) {
					if (item.getAlias() != null && item.getAlias().length() > 0) {
						aggrColumns.put(item.getAlias(), mergeType);
					} else { // 如果不加，jdbc方式时取不到正确结果 ;修改添加别名
						item.setAlias(method + i);
						aggrColumns.put(method + i, mergeType);
						isNeedChangeSql = true;
					}
					rrs.setHasAggrColumn(true);
					havingColsName.add(item.getAlias()); // Added by winbill,
															// 20160314, for
															// having clause
					havingColsName.add(""); // Added by winbill, 20160314, one
											// alias for non-AVG
				}
			} else {
				if (!(item.getExpr() instanceof SQLAllColumnExpr)) {
					String alia = item.getAlias();
					String field = getFieldName(item);
					if (alia == null) {
						alia = field;
					}
					aliaColumns.put(field, alia);
				}
			}

		}
		if (aggrColumns.size() > 0) {
			rrs.setMergeCols(aggrColumns);
		}

		// 通过优化转换成group by来实现
		if (isDistinct) {
			mysqlSelectQuery.setDistionOption(0);
			SQLSelectGroupByClause groupBy = new SQLSelectGroupByClause();
			for (String fieldName : aliaColumns.keySet()) {
				groupBy.addItem(new SQLIdentifierExpr(fieldName));
			}
			mysqlSelectQuery.setGroupBy(groupBy);
			isNeedChangeSql = true;
		}

		// setGroupByCols
		if (mysqlSelectQuery.getGroupBy() != null) {
			List<SQLExpr> groupByItems = mysqlSelectQuery.getGroupBy().getItems();
			String[] groupByCols = buildGroupByCols(groupByItems, aliaColumns);
			rrs.setGroupByCols(groupByCols);
			rrs.setHavings(buildGroupByHaving(mysqlSelectQuery.getGroupBy().getHaving()));
			rrs.setHasAggrColumn(true);
			rrs.setHavingColsName(havingColsName.toArray()); // Added by
																// winbill,
																// 20160314, for
																// having clause
		}

		if (isNeedChangeSql) {
			String sql = stmt.toString();
			rrs.changeNodeSqlAfterAddLimit(schema, sql, 0, -1);
			getCtx().setSql(sql);
		}
		return aliaColumns;
	}

	private HavingCols buildGroupByHaving(SQLExpr having) {
		if (having == null) {
			return null;
		}

		SQLBinaryOpExpr expr = ((SQLBinaryOpExpr) having);
		SQLExpr left = expr.getLeft();
		SQLBinaryOperator operator = expr.getOperator();
		SQLExpr right = expr.getRight();

		String leftValue = null;
		;
		if (left instanceof SQLAggregateExpr) {
			leftValue = ((SQLAggregateExpr) left).getMethodName() + "("
					+ ((SQLAggregateExpr) left).getArguments().get(0) + ")";
		} else if (left instanceof SQLIdentifierExpr) {
			leftValue = ((SQLIdentifierExpr) left).getName();
		}

		String rightValue = null;
		if (right instanceof SQLNumericLiteralExpr) {
			rightValue = right.toString();
		} else if (right instanceof SQLTextLiteralExpr) {
			rightValue = StringUtil.removeBackquote(right.toString());
		}

		return new HavingCols(leftValue, rightValue, operator.getName());
	}

	protected boolean isRoutMultiNode(SchemaConfig schema, RouteResultset rrs) {
		if (rrs.getNodes() != null && rrs.getNodes().length > 1) {
			return true;
		}
		LayerCachePool tableId2DataNodeCache = (LayerCachePool) MycatServer.getInstance().getCacheService()
				.getCachePool("TableID2DataNodeCache");
		try {
			tryRoute(schema, rrs, tableId2DataNodeCache);
			if (rrs.getNodes() != null && rrs.getNodes().length > 1) {
				return true;
			}
		} catch (SQLNonTransientException e) {
			throw new RuntimeException(e);
		}
		return false;
	}

	private String getFieldName(SQLSelectItem item) {
		if ((item.getExpr() instanceof SQLPropertyExpr) || (item.getExpr() instanceof SQLMethodInvokeExpr)
				|| (item.getExpr() instanceof SQLIdentifierExpr) || item.getExpr() instanceof SQLBinaryOpExpr) {
			return item.getExpr().toString();// 字段别名
		} else {
			return item.toString();
		}
	}

	protected void tryRoute(SchemaConfig schema, RouteResultset rrs, LayerCachePool cachePool)
			throws SQLNonTransientException {
		if (rrs.isFinishedRoute()) {
			return;// 避免重复路由
		}

		// 无表的select语句直接路由带任一节点
		if ((ctx.getTables() == null || ctx.getTables().size() == 0)
				&& (ctx.getTableAliasMap() == null || ctx.getTableAliasMap().isEmpty())) {
			rrs = RouterUtil.routeToSingleNode(rrs, schema.getRandomDataNode(), ctx.getSql());
			rrs.setFinishedRoute(true);
			return;
		}
		// RouterUtil.tryRouteForTables(schema, ctx, rrs, true, cachePool);
		SortedSet<RouteResultsetNode> nodeSet = new TreeSet<RouteResultsetNode>();
		boolean isAllGlobalTable = RouterUtil.isAllGlobalTable(ctx, schema);
		for (RouteCalculateUnit unit : ctx.getRouteCalculateUnits()) {
			RouteResultset rrsTmp = RouterUtil.tryRouteForTables(schema, ctx, unit, rrs, true, cachePool);
			if (rrsTmp != null && rrsTmp.getNodes() != null) {
				for (RouteResultsetNode node : rrsTmp.getNodes()) {
					nodeSet.add(node);
				}
			}
			if (isAllGlobalTable) {// 都是全局表时只计算一遍路由
				break;
			}
		}

		if (nodeSet.size() == 0) {

			Collection<String> stringCollection = ctx.getTableAliasMap().values();
			for (String table : stringCollection) {
				if (table != null && table.toLowerCase().contains("information_schema.")) {
					rrs = RouterUtil.routeToSingleNode(rrs, schema.getRandomDataNode(), ctx.getSql());
					rrs.setFinishedRoute(true);
					return;
				}
			}
			String msg = " find no Route:" + ctx.getSql();
			LOGGER.warn(msg);
			throw new SQLNonTransientException(msg);
		}

		RouteResultsetNode[] nodes = new RouteResultsetNode[nodeSet.size()];
		int i = 0;
		for (Iterator<RouteResultsetNode> iterator = nodeSet.iterator(); iterator.hasNext();) {
			nodes[i] = (RouteResultsetNode) iterator.next();
			i++;

		}

		rrs.setNodes(nodes);
		rrs.setFinishedRoute(true);
	}

	protected String getAliaColumn(Map<String, String> aliaColumns, String column) {
		String alia = aliaColumns.get(column);
		if (alia == null) {
			if (column.indexOf(".") < 0) {
				String col = "." + column;
				String col2 = ".`" + column + "`";
				// 展开aliaColumns，将<c.name,cname>之类的键值对展开成<c.name,cname>和<name,cname>
				for (Map.Entry<String, String> entry : aliaColumns.entrySet()) {
					if (entry.getKey().endsWith(col) || entry.getKey().endsWith(col2)) {
						if (entry.getValue() != null && entry.getValue().indexOf(".") > 0) {
							return column;
						}
						return entry.getValue();
					}
				}
			}

			return column;
		} else {
			return alia;
		}
	}

	private String[] buildGroupByCols(List<SQLExpr> groupByItems, Map<String, String> aliaColumns) {
		String[] groupByCols = new String[groupByItems.size()];
		for (int i = 0; i < groupByItems.size(); i++) {
			SQLExpr sqlExpr = groupByItems.get(i);
			String column = null;
			if (sqlExpr instanceof SQLIdentifierExpr) {
				column = ((SQLIdentifierExpr) sqlExpr).getName();
			} else if (sqlExpr instanceof SQLMethodInvokeExpr) {
				column = ((SQLMethodInvokeExpr) sqlExpr).toString();
			} else if (sqlExpr instanceof MySqlOrderingExpr) {
				// todo czn
				SQLExpr expr = ((MySqlOrderingExpr) sqlExpr).getExpr();

				if (expr instanceof SQLName) {
					column = StringUtil.removeBackquote(((SQLName) expr).getSimpleName());// 不要转大写
																							// 2015-2-10
																							// sohudo
																							// StringUtil.removeBackquote(expr.getSimpleName().toUpperCase());
				} else {
					column = StringUtil.removeBackquote(expr.toString());
				}
			} else if (sqlExpr instanceof SQLPropertyExpr) {
				/**
				 * 针对子查询别名，例如select id from (select h.id from hotnews h union
				 * select h.title from hotnews h ) as t1 group by t1.id;
				 */
				column = sqlExpr.toString();
			}
			if (column == null) {
				column = sqlExpr.toString();
			}
			int dotIndex = column.indexOf(".");
			int bracketIndex = column.indexOf("(");
			// 通过判断含有括号来决定是否为函数列
			if (dotIndex != -1 && bracketIndex == -1) {
				// 此步骤得到的column必须是不带.的，有别名的用别名，无别名的用字段名
				column = column.substring(dotIndex + 1);
			}
			groupByCols[i] = getAliaColumn(aliaColumns, column);// column;
		}
		return groupByCols;
	}

	protected LinkedHashMap<String, Integer> buildOrderByCols(List<SQLSelectOrderByItem> orderByItems,
			Map<String, String> aliaColumns) {
		LinkedHashMap<String, Integer> map = new LinkedHashMap<String, Integer>();
		for (int i = 0; i < orderByItems.size(); i++) {
			SQLOrderingSpecification type = orderByItems.get(i).getType();
			// orderColumn只记录字段名称,因为返回的结果集是不带表名的。
			SQLExpr expr = orderByItems.get(i).getExpr();
			String col;
			if (expr instanceof SQLName) {
				col = ((SQLName) expr).getSimpleName();
			} else {
				col = expr.toString();
			}
			if (type == null) {
				type = SQLOrderingSpecification.ASC;
			}
			col = getAliaColumn(aliaColumns, col);// 此步骤得到的col必须是不带.的，有别名的用别名，无别名的用字段名
			map.put(col,
					type == SQLOrderingSpecification.ASC ? OrderCol.COL_ORDER_TYPE_ASC : OrderCol.COL_ORDER_TYPE_DESC);
		}
		return map;
	}
}