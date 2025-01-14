package com.baomidou.mybatisplus.core.batch;

/**
 * @author nieqiurong
 * @since 3.5.4
 */
@FunctionalInterface
public interface ParameterConvert<T> {

    /**
     * 转换当前实体参数为mapper方法参数
     *
     * @param parameter 参数对象
     * @return mapper方法参数.
     */
    Object convert(T parameter);

}
