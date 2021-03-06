package de.evoila.cf.autoscaler.core.kafka.consumer;

import de.evoila.cf.autoscaler.core.manager.ScalableAppManager;
import de.evoila.cf.autoscaler.kafka.AutoScalerConsumer;
import de.evoila.cf.autoscaler.kafka.ByteConsumerThread;

public abstract class AbstractByteConsumer implements AutoScalerConsumer {

	ByteConsumerThread consThread;
	ScalableAppManager appManager;
	
	long maxMetricAge;
	
	public AbstractByteConsumer(String topic, String groupId, String hostname, int port, long maxMetricAge, ScalableAppManager appManager) {
		consThread = new ByteConsumerThread(topic, groupId, hostname, port, this);
		this.appManager = appManager;
		this.maxMetricAge = maxMetricAge;
	}
	
	/**
	 * Start underlying {@linkplain #consThread ByteConsumerThread}. 
	 */
	public void startConsumer() {
		consThread.start();
	}
	
	/**
	 * Stops the underlying {@linkplain ByteConsumerThread}.
	 */
	public void stopConsumer() {
		consThread.getKafkaConsumer().wakeup();
	}
	
	/**
	 * Abstract consumer method for byte consumption.
	 * Needs to be implemented.
	 */
	public abstract void consume(byte[] bytes);
	
	/**
	 * Abstract method for returning a string representation of the consumer type.
	 * Needs to be implemented.
	 */
	public abstract String getType();
}
