package com.baomidou.mybatisplus.core.batch;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.enums.SqlMethod;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.core.toolkit.StringPool;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * <li>事务需要自行控制</li>
 * <li>批次数据尽量自行切割处理</li>
 * <li>返回值为批处理结果,如果对返回值比较关心的可接收判断处理</li>
 * <li>saveOrUpdate尽量少用把,保持批处理为简单的插入或更新</li>
 * <li>关于saveOrUpdate中的sqlSession,如果执行了select操作的话,BatchExecutor都会触发一次flushStatements,为了保证结果集,故使用包装了部分sqlSession查询操作</li>
 * <li>autoCommit参数,在spring下使用的是{@link org.mybatis.spring.transaction.SpringManagedTransaction},控制无效,只能通过datasource控制(建议不要修改),单独使用mybatis下{@link org.apache.ibatis.transaction.jdbc.JdbcTransaction}是可用的</li>
 * <pre>
 *     Spring示例:
 * 		transactionTemplate.execute(new TransactionCallback<List<BatchResult>>() {
 *            {@code @Override}
 * 			public List<BatchResult> doInTransaction(TransactionStatus status) {
 * 				MybatisBatch.Method<Demo> method = new MybatisBatch.Method<>(DemoMapper.class);
 * 				return new MybatisBatch<>(sqlSessionFactory,demoList).execute(true, method.insert());
 *            }
 *        });
 * </pre>
 *
 * @author nieqiurong
 * @since 3.5.4
 */
public class MybatisBatch<T> {

    private final SqlSessionFactory sqlSessionFactory;

    private final List<T> dataList;

    public MybatisBatch(SqlSessionFactory sqlSessionFactory, List<T> dataList) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.dataList = dataList;
    }

    /**
     * 执行批量操作
     *
     * @param statement 执行的 mapper 方法 (示例: com.baomidou.mybatisplus.core.mapper.BaseMapper.insert )
     * @return 批处理结果
     */
    public List<BatchResult> execute(String statement) {
        return execute(false, statement, (entity) -> entity);
    }

    /**
     * 执行批量操作
     *
     * @param statement        执行的 mapper 方法 (示例: com.baomidou.mybatisplus.core.mapper.BaseMapper.insert )
     * @param parameterConvert 参数转换器
     * @return 批处理结果
     */
    public List<BatchResult> execute(String statement, ParameterConvert<T> parameterConvert) {
        return execute(false, statement, parameterConvert);
    }

    /**
     * 执行批量操作
     *
     * @param autoCommit 是否自动提交(这里生效的前提依赖于事务管理器 {@link org.apache.ibatis.transaction.Transaction})
     * @param statement  执行的 mapper 方法 (示例: com.baomidou.mybatisplus.core.mapper.BaseMapper.insert )
     * @return 批处理结果
     */
    public List<BatchResult> execute(boolean autoCommit, String statement) {
        return execute(autoCommit, statement, (entity) -> entity);
    }

    /**
     * 执行批量操作
     *
     * @param batchMethod 批量操作方法
     * @return 批处理结果
     */
    public List<BatchResult> execute(BatchMethod<T> batchMethod) {
        return execute(false, batchMethod);
    }


    /**
     * 执行批量操作
     *
     * @param autoCommit  是否自动提交(这里生效的前提依赖于事务管理器 {@link org.apache.ibatis.transaction.Transaction})
     * @param batchMethod 批量操作方法
     * @return 批处理结果
     */
    public List<BatchResult> execute(boolean autoCommit, BatchMethod<T> batchMethod) {
        return execute(autoCommit, batchMethod.getStatementId(), batchMethod.getParameterConvert());
    }

    /**
     * 执行批量操作
     *
     * @param autoCommit       是否自动提交(这里生效的前提依赖于事务管理器 {@link org.apache.ibatis.transaction.Transaction})
     * @param statement        执行的 mapper 方法 (示例: com.baomidou.mybatisplus.core.mapper.BaseMapper.insert )
     * @param parameterConvert 参数转换器
     * @return 批处理结果
     */
    public List<BatchResult> execute(boolean autoCommit, String statement, ParameterConvert<T> parameterConvert) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH, autoCommit)) {
            for (T data : dataList) {
                sqlSession.update(statement, toParameter(parameterConvert, data));
            }
            List<BatchResult> resultList = sqlSession.flushStatements();
            if(!autoCommit) {
                sqlSession.commit();
            }
            return resultList;
        }
    }

    /**
     * 批量保存或更新
     *
     * @param insertMethod    插入方法
     * @param insertPredicate 插入条件 (当条件满足时执行插入方法,否则执行更新方法)
     * @param updateMethod    更新方法
     * @return 批处理结果
     */
    public List<BatchResult> saveOrUpdate(BatchMethod<T> insertMethod, BiPredicate<BatchSqlSession, T> insertPredicate, BatchMethod<T> updateMethod) {
        return saveOrUpdate(false, insertMethod, insertPredicate, updateMethod);
    }

    /**
     * 批量保存或更新
     *
     * @param autoCommit      是否自动提交(这里生效的前提依赖于事务管理器 {@link org.apache.ibatis.transaction.Transaction})
     * @param insertMethod    插入方法
     * @param insertPredicate 插入条件 (当条件满足时执行插入方法,否则执行更新方法)
     * @param updateMethod    更新方法
     * @return 批处理结果
     */
    public List<BatchResult> saveOrUpdate(boolean autoCommit, BatchMethod<T> insertMethod, BiPredicate<BatchSqlSession, T> insertPredicate, BatchMethod<T> updateMethod) {
        List<BatchResult> resultList = new ArrayList<>();
        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH, autoCommit)) {
            BatchSqlSession session = new BatchSqlSession(sqlSession);
            for (T data : dataList) {
                if (insertPredicate.test(session, data)) {
                    sqlSession.insert(insertMethod.getStatementId(), toParameter(insertMethod.getParameterConvert(), data));
                } else {
                    sqlSession.update(updateMethod.getStatementId(), toParameter(updateMethod.getParameterConvert(), data));
                }
            }
            resultList.addAll(sqlSession.flushStatements());
            resultList.addAll(session.getResultBatchList());
            if(!autoCommit) {
                sqlSession.commit();
            }
            return resultList;
        }
    }

    /**
     * 参数转换
     *
     * @param parameterConvert 参数转换器
     * @param data             参数
     * @return 方法参数
     */
    protected Object toParameter(ParameterConvert<T> parameterConvert, T data) {
        return parameterConvert != null ? parameterConvert.convert(data) : data;
    }

    /**
     * 内置方法简化调用
     *
     * @param <T> 泛型参数(实体)
     */
    public static class Method<T> {

        /**
         * 命名空间
         */
        private final String namespace;

        public Method(Class<?> mapperClass) {
            this.namespace = mapperClass.getName();
        }

        /**
         * 新增方法
         *
         * @return 新增方法
         */
        public BatchMethod<T> insert() {
            return new BatchMethod<>(namespace + StringPool.DOT + SqlMethod.INSERT_ONE.getMethod());
        }

        /**
         * 新增方法
         *
         * @param function 转换函数
         * @param <E>      实体
         * @return 新增方法
         */
        public <E> BatchMethod<E> insert(Function<E, T> function) {
            return new BatchMethod<>(namespace + StringPool.DOT + SqlMethod.INSERT_ONE.getMethod(), function::apply);
        }

        /**
         * 更新方法 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper#updateById(java.lang.Object)}
         *
         * @return 更新方法
         */
        public BatchMethod<T> updateById() {
            return new BatchMethod<>(namespace + StringPool.DOT + SqlMethod.UPDATE_BY_ID.getMethod(), (entity) -> {
                Map<String, Object> param = new HashMap<>();
                param.put(Constants.ENTITY, entity);
                return param;
            });
        }

        /**
         * 更新方法 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper#updateById(java.lang.Object)}
         *
         * @param etFunction 实体转换
         * @param <E>        实体
         * @return 更新方法
         */
        public <E> BatchMethod<E> updateById(Function<E, T> etFunction) {
            return new BatchMethod<>(namespace + StringPool.DOT + SqlMethod.UPDATE_BY_ID.getMethod(), (parameter) -> {
                Map<String, Object> param = new HashMap<>();
                param.put(Constants.ENTITY, etFunction.apply(parameter));
                return param;
            });
        }

        /**
         * 更新方法 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper#update(java.lang.Object, com.baomidou.mybatisplus.core.conditions.Wrapper)}
         *
         * @param wrapperFunction 更新条件(不能为null)
         * @param <E>             实体
         * @return 更新方法
         */
        public <E> BatchMethod<E> update(Function<E, Wrapper<T>> wrapperFunction) {
            return new BatchMethod<>(namespace + StringPool.DOT + SqlMethod.UPDATE.getMethod(), (parameter) -> {
                Map<String, Object> param = new HashMap<>();
                param.put(Constants.WRAPPER, wrapperFunction.apply(parameter));
                return param;
            });
        }

        /**
         * 更新方法 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper#update(java.lang.Object, com.baomidou.mybatisplus.core.conditions.Wrapper)}
         *
         * @param entityFunction  实体参数
         * @param wrapperFunction wrapper参数
         * @param <E>             实体
         * @return 更新方法
         */
        public <E> BatchMethod<E> update(Function<E, T> entityFunction, Function<E, Wrapper<T>> wrapperFunction) {
            return new BatchMethod<>(namespace + StringPool.DOT + SqlMethod.UPDATE.getMethod(), (parameter) -> {
                Map<String, Object> param = new HashMap<>();
                param.put(Constants.ENTITY, entityFunction.apply(parameter));
                param.put(Constants.WRAPPER, wrapperFunction.apply(parameter));
                return param;
            });
        }

        /**
         * 删除方法 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper#deleteById(Object)} or {@link com.baomidou.mybatisplus.core.mapper.BaseMapper#deleteById(Serializable)}
         *
         * @param function 参数转换
         * @param <E>      实体
         * @return 删除方法
         */
        public <E> BatchMethod<E> deleteById(Function<E, T> function) {
            return new BatchMethod<>(namespace + StringPool.DOT + SqlMethod.DELETE_BY_ID.getMethod(), function::apply);
        }

        /**
         * 删除方法 {@link com.baomidou.mybatisplus.core.mapper.BaseMapper#deleteById(Object)} or {@link com.baomidou.mybatisplus.core.mapper.BaseMapper#deleteById(Serializable)}
         *
         * @param <T> 实体
         * @return 删除方法
         */
        @SuppressWarnings("TypeParameterHidesVisibleType")
        public <T> BatchMethod<T> deleteById() {
            return new BatchMethod<>(namespace + StringPool.DOT + SqlMethod.DELETE_BY_ID.getMethod());
        }

        public <E> BatchMethod<E> get(String method) {
            return new BatchMethod<>(namespace + StringPool.DOT + method);
        }

        public <E> BatchMethod<E> get(String method, ParameterConvert<E> parameterConvert) {
            return new BatchMethod<>(namespace + StringPool.DOT + method, parameterConvert);
        }

    }

}
