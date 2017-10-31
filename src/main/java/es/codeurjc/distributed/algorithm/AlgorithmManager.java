package es.codeurjc.distributed.algorithm;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.config.XmlClientConfigBuilder;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.hazelcast.core.IQueue;

public class AlgorithmManager<R> {
	
	private static final Logger log = LoggerFactory.getLogger(AlgorithmManager.class);
	
	HazelcastInstance hzClient;
	Map<String, Algorithm<R>> algorithms;
	Map<String, WorkerStats> workers;
	IMap<String, QueueProperty> QUEUES;
		
	public AlgorithmManager(String HAZELCAST_CLIENT_CONFIG) {
		
		ClientConfig config = new ClientConfig();
		try {
			config = new XmlClientConfigBuilder(HAZELCAST_CLIENT_CONFIG).build();
		} catch (IOException e) {
			e.printStackTrace();
		}
		this.hzClient = HazelcastClient.newHazelcastClient(config);
		
		this.hzClient.getCluster().addMembershipListener(new ClusterMembershipListener(this));

		this.algorithms = new ConcurrentHashMap<>();
		this.workers = new ConcurrentHashMap<>();
		
		this.QUEUES = this.hzClient.getMap("QUEUES");

		hzClient.getTopic("algorithm-solved").addMessageListener((message) -> {
			AlgorithmEvent ev = (AlgorithmEvent) message.getMessageObject();
			log.info("ALGORITHM SOLVED: Algorithm: " + ev.getAlgorithmId() + ", Result: " + ev.getContent());
			Algorithm<R> alg = this.algorithms.get(ev.getAlgorithmId());
			alg.setFinishTime(System.currentTimeMillis());
			try {
				alg.setResult((R) ev.getContent());
				alg.runCallback();
			} catch(Exception e) {
				log.error(e.getMessage());
			}
			
			// Remove distributed queue
			this.QUEUES.remove(ev.getAlgorithmId());
		});
		hzClient.getTopic("queue-stats").addMessageListener((message) -> {
			AlgorithmEvent ev = (AlgorithmEvent) message.getMessageObject();
			log.info("EXECUTOR STATS for queue [{}]: Tasks waiting in queue -> {}", ev.getAlgorithmId(), ev.getContent());
			Algorithm<R> alg = this.algorithms.get(ev.getAlgorithmId());
			alg.setTasksQueued((int) ev.getContent());
		});
		hzClient.getTopic("task-completed").addMessageListener((message) -> {
			AlgorithmEvent ev = (AlgorithmEvent) message.getMessageObject();
			log.info("TASK [{}] completed for algorithm [{}] with result [{}]", ev.getContent(), ev.getAlgorithmId(), ((Task<?>)ev.getContent()).getResult());
			Algorithm<R> alg = this.algorithms.get(ev.getAlgorithmId());
			alg.incrementTasksCompleted();
		});
		hzClient.getTopic("worker-stats").addMessageListener((message) -> {
			WorkerEvent ev = (WorkerEvent) message.getMessageObject();
			log.info("WORKER EVENT for worker [{}]: {}", ev.getWorkerId(), ev.getContent());
			this.workers.put(ev.getWorkerId(), (WorkerStats) ev.getContent());
		});
	}

	public Algorithm<R> getAlgorithm(String algorithmId) {
		return this.algorithms.get(algorithmId);
	}
	
	public void solveAlgorithm(String id, Task<?> initialTask, Integer priority) throws Exception {
		Algorithm<R> alg = new Algorithm<>(id, priority, initialTask);
		this.algorithms.put(id, alg);
		
		IQueue<Task<?>> queue = this.hzClient.getQueue(alg.getId());
		QUEUES.put(alg.getId(), new QueueProperty(alg.getPriority(), new AtomicInteger((int) System.currentTimeMillis())));
		
		alg.solve(queue);
	}

	public void solveAlgorithm(String id, Task<?> initialTask, Integer priority, Consumer<R> callback) throws Exception {
		Algorithm<R> alg = new Algorithm<>(id, priority, initialTask, callback);
		this.algorithms.put(id, alg);
		
		IQueue<Task<?>> queue = this.hzClient.getQueue(alg.getId());
		QUEUES.put(alg.getId(), new QueueProperty(alg.getPriority(), new AtomicInteger((int) System.currentTimeMillis())));
		
		alg.solve(queue);
	}
	
	public Map<String, WorkerStats> getWorkers() {
		return this.workers;
	}
	
	public void terminateAlgorithms() {
		this.hzClient.getTopic("stop-algorithms").publish("");
	}

}
