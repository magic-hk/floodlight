package net.floodlightcontroller.routing.algorithms;

import java.util.Map;

import net.floodlightcontroller.core.module.IFloodlightService;

public interface IAlgorithmService extends IFloodlightService{
	/**
	 * 获取默认路由算法
	 * @return
	 */
	public RoutingAlgorithmBase getDefaultRoutingAlgorithm();
	
	/**
	 * 获取所有的路由算法
	 * @return
	 */
	
	public Map<String, RoutingAlgorithmBase> getAllRoutingAlgorithms();
	
	/**
	 * 获取key hash计算算法实例
	 * @return
	 */
	public KeyHashAlgorithmBase getKeyHashAlgorithm();

	/**
	 * 根据keyHash获取算法实例
	 * @param keyHash
	 * @return
	 */
	public RoutingAlgorithmBase getRoutingAlgorithmByKeyHash(String keyHash);
}
