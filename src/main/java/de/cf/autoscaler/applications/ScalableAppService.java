package de.cf.autoscaler.applications;

import java.util.LinkedList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.cf.autoscaler.api.binding.Binding;
import de.cf.autoscaler.exception.InvalidPolicyException;
import de.cf.autoscaler.exception.InvalidWorkingSetException;
import de.cf.autoscaler.exception.LimitException;
import de.cf.autoscaler.exception.SpecialCharacterException;
import de.cf.autoscaler.exception.TimeException;
import de.cf.autoscaler.http.response.ResponseApplication;
import de.cf.autoscaler.kafka.messages.ApplicationMetric;
import de.cf.autoscaler.kafka.messages.AutoscalerMetric;
import de.cf.autoscaler.kafka.messages.ContainerMetric;
import de.cf.autoscaler.kafka.messages.HttpMetric;
import de.cf.autoscaler.kafka.producer.ProtobufProducer;
import de.cf.autoscaler.kafka.protobuf.ProtobufApplicationMetricWrapper.ProtoApplicationMetric;

/**
 * Holds helper methods for {@linkplain ScalableApp}:
 * <ul>
 * 		<li>validation of ScalableApps</li>
 * 		<li>creation of an json serialization object</li>
 * </ul>
 * @author Marius Berger
 *
 */
public class ScalableAppService {
	
	/**
	 * Logger of this class.
	 */
	private static Logger log = LoggerFactory.getLogger(ScalableAppService.class);

	/**
	 * Private constructor as there is no need for an object of this class.
	 */
	private ScalableAppService() { }
	
	/**
	 * Returns a {@linkplain ResponseApplication} object for serialization with locking and unlocking the {@linkplain ScalableApp}.
	 * Make sure that the application is not locked before calling this method, as this will most likely generate a deadlock.
	 * @param app {@linkplain ScalableApp} to get fields from.
	 * @return {@linkplain ResponseApplication} object for serialization
	 */
	public static ResponseApplication getSerializationObjectWithLock(ScalableApp app) {
		ResponseApplication responseApp = null;
		try {
			app.acquire();
			responseApp = new ResponseApplication(app);
		} catch (InterruptedException ex) {
			return null;
		}
		app.release();
		return responseApp;
	}
	
	/**
	 * Returns a {@linkplain ResponseApplication} object for serialization without locking and unlocking the {@linkplain ScalableApp}.
	 * @param app {@linkplain ScalableApp} to get fields from.
	 * @return {@linkplain ResponseApplication} object for serialization
	 */
	public static ResponseApplication getSerializationObjectWithoutLock(ScalableApp app) {
		return new ResponseApplication(app);
	}
	
	/**
	 * Validates an {@linkplain AppBlueprint}. Valid Blueprints can be used to create a {@linkplain ScalableApp}.
	 * @param bp blueprint to get fields from
	 * @return true if blueprint is valid
	 * @throws LimitException for invalid limits and numbers
	 * @throws InvalidPolicyException for invalid policies
	 * @throws SpecialCharacterException for invalid names and IDs
	 * @throws TimeException for invalid time stamps and number concerning time.
	 * @throws InvalidWorkingSetException for invalid working sets
	 */
	public static boolean isValid(AppBlueprint bp) throws LimitException, InvalidPolicyException,
								SpecialCharacterException, TimeException, InvalidWorkingSetException {
		
		return  (bp == null || bp.getBinding() == null) 
				&&
				areValidBindingInformation(bp.getBinding())
				&&
				areValidPolicies(bp.getRequestThresholdPolicy(), 
								bp.getCpuThresholdPolicy(), 
								bp.getRamThresholdPolicy(),
								bp.getLatencyThresholdPolicy())
				&&
				areValidLimits(	bp.getCpuUpperLimit(),
								bp.getCpuLowerLimit(),
								bp.getRamUpperLimit(),
								bp.getRamLowerLimit(),
								bp.getLatencyUpperLimit(),
								bp.getLatencyLowerLimit(),
								bp.getMinQuotient(),
								bp.getMinInstances(),
								bp.getMaxInstances(),
								bp.getCooldownTime(),
								bp.getLearningTimeMultiplier(),
								bp.getScalingIntervalMultiplier())
				&&
				isValidWorkingSet(bp.getCurrentIntervalState(),
								bp.getScalingIntervalMultiplier(),
								bp.getLastScalingTime(),
								bp.getLearningStartTime(),
								bp.getBinding().getCreationTime());
	}
	
	/**
	 * Checks whether the given working set is in a valid state in regards to the Autoscaler.
	 * @param currentScalingInterval currentScalingInterval of the {@linkplain ScalableApp}
	 * @param scalingIntervalMultiplier scalingIntervalMultiplier of the {@linkplain ScalableApp}
	 * @param lastScalingTime lastScalingTime of the {@linkplain ScalableApp}
	 * @param learningStartTime learningStartTime of the {@linkplain ScalableApp}
	 * @param creationTime creationTime of the {@linkplain ScalableApp}
	 * @return true if the working set is valid
	 * @throws InvalidWorkingSetException if an invalid working set is found
	 * @throws TimeException if an invalid time variable is found
	 */
	private static boolean isValidWorkingSet(int currentScalingInterval, int scalingIntervalMultiplier,
			long lastScalingTime, long learningStartTime, long creationTime) throws InvalidWorkingSetException, TimeException {
		if (currentScalingInterval < 0 || currentScalingInterval > scalingIntervalMultiplier) 
			throw new InvalidWorkingSetException("CurrentScalingInterval is smaller than 0 or bigger than scalingIntervalMultiplier.");
		if (lastScalingTime < 0 || lastScalingTime < creationTime) 
			throw new TimeException("LastScalingTime is smaller than 0 or smaller than creationTime.");
		if (learningStartTime < 0 || learningStartTime < creationTime)
			throw new TimeException("LearningStartTime is smaller than 0 or smaller than creationTime.");
		if (creationTime < 0) 
			throw new TimeException("CreationTime is smaller than 0.");
		return true;
	}
	
	/**
	 * Checks whether the given binding information are in a valid state in regards to the Autoscaler.
	 * @param binding wrapper class for the binding information
	 * @return true if the names are valid
	 * @throws SpecialCharacterException if an special character was found
	 */
	private static boolean areValidBindingInformation(Binding binding) throws SpecialCharacterException {
		if (!binding.getResourceId().matches("\\w*")) {
			for (int i = 0; i < binding.getResourceId().length(); i++) {
				if (String.valueOf(binding.getResourceId().charAt(i)).matches("\\W*") && binding.getResourceId().charAt(i)!= '-' ) {
					throw new SpecialCharacterException("AppId contains special characters.");
				}
			}
		}	
		
		return true;
	}
	
	/**
	 * Checks whether the given policies are in a valid state in regards to the Autoscaler.
	 * @param requestThresholdPolicy requestThresholdPolicy of the {@linkplain ScalableApp}
	 * @param cpuThresholdPolicy cpuThresholdPolicy of the {@linkplain ScalableApp}
	 * @param ramThresholdPolicy ramThresholdPolicy of the {@linkplain ScalableApp}
	 * @param latencyThresholdPolicy latencyThresholdPolicy of the {@linkplain ScalableApp}
	 * @return true if the policies are valid
	 * @throws InvalidPolicyException if an invalid policy is found
	 */
	private static boolean areValidPolicies(String requestThresholdPolicy,String cpuThresholdPolicy,
			String ramThresholdPolicy, String latencyThresholdPolicy) throws InvalidPolicyException {
		
		if (requestThresholdPolicy == null) 
			throw new InvalidPolicyException("HttpThresholdPolicy is null.");
		if (cpuThresholdPolicy == null)
			throw new InvalidPolicyException("CpuThresholdPolicy is null.");
		if (ramThresholdPolicy == null)
			throw new InvalidPolicyException("RamThresholdPolicy is null.");
		if (latencyThresholdPolicy == null)
			throw new InvalidPolicyException("LatencyThresholdPolicy is null.");
		
		List<String> policies = new LinkedList<String>();
		policies.add(null);
		policies.add(ScalableApp.MAX);
		policies.add(ScalableApp.MIN);
		policies.add(ScalableApp.MEAN);
		
		if (!policies.contains(requestThresholdPolicy))
			throw new InvalidPolicyException("HttpThresholdPolicy is invalid.");
		if (!policies.contains(cpuThresholdPolicy))
			throw new InvalidPolicyException("CpuThresholdPolicy is invalid.");
		if (!policies.contains(ramThresholdPolicy))
			throw new InvalidPolicyException("RamThresholdPolicy is invalid.");
		if (!policies.contains(latencyThresholdPolicy))
			throw new InvalidPolicyException("LatencyThresholdPolicy is invalid.");
		
		return true;
	}
	
	/**
	 * Checks whether the given limits are in a valid state in regards to the Autoscaler.
	 * @param cpuUpperLimit cpuUpperLimit of the {@linkplain ScalableApp}
	 * @param cpuLowerLimit cpuLowerLimit of the {@linkplain ScalableApp}
	 * @param ramUpperLimit ramUpperLimit of the {@linkplain ScalableApp}
	 * @param ramLowerLimit ramLowerLimit of the {@linkplain ScalableApp}
	 * @param latencyUpperLimit latencyUpperLimit of the {@linkplain ScalableApp}
	 * @param latencyLowerLimit latencyLowerLimit of the {@linkplain ScalableApp}
	 * @param minQuotient minQuotient of the {@linkplain ScalableApp}
	 * @param minInstances minInstances of the {@linkplain ScalableApp}
	 * @param maxInstances maxInstances of the {@linkplain ScalableApp}
	 * @param cooldownTime cooldownTime of the {@linkplain ScalableApp}
	 * @param learningTimeMultiplier learningTimeMultiplier of the {@linkplain ScalableApp}
	 * @param scalingIntervalMultiplier scalingIntervalMultiplier of the {@linkplain ScalableApp}
	 * @return true if the limits are valid
	 * @throws LimitException if an invalid limit is found
	 */
	private static boolean areValidLimits(int cpuUpperLimit, int cpuLowerLimit, long ramUpperLimit, long ramLowerLimit, int latencyUpperLimit, int latencyLowerLimit
			, int minQuotient, int minInstances, int maxInstances, int cooldownTime, int learningTimeMultiplier
			, int scalingIntervalMultiplier) throws LimitException{
		
		if (cpuUpperLimit <= cpuLowerLimit)
			throw new LimitException("CpuUpperLimit is smaller than or equals CpuLowerLimit.");
		if (cpuUpperLimit > 100)
			throw new LimitException("CpuUpperLimit is bigger than 100.");
		if (cpuLowerLimit < 0)
			throw new LimitException("CpuLowerLimit is smaller than 0.");
		if (ramUpperLimit <= ramLowerLimit) 
			throw new LimitException("RamUpperLimit is smaller than or equals RamLowerLimit.");
		if (ramUpperLimit > Integer.MAX_VALUE)
			throw new LimitException("RamUpperLimit is bigger than "+Integer.MAX_VALUE+".");
		if (ramLowerLimit < 0)
			throw new LimitException("RamLowerLimit is smaller than 0.");
		if (latencyUpperLimit > Integer.MAX_VALUE)
			throw new LimitException("LatencyUpperLimit is bigger than "+Integer.MAX_VALUE+".");
		if (latencyLowerLimit < 0) 
			throw new LimitException("LatencyLowerLimit is smaller than 0.");
		if (minQuotient < 0)
			throw new LimitException("minQuotient is smaller than 0.");
		if (minInstances < 0)	
			throw new LimitException("MinInstances is smaller than 0");
		if (maxInstances < minInstances)
			throw new LimitException("MaxInstances is smaller than MinInstances.");
		if (cooldownTime < ScalableApp.COOLDOWN_MIN)
			throw new LimitException("CooldownTime is smaller than "+ScalableApp.COOLDOWN_MIN+".");
		if (learningTimeMultiplier < ScalableApp.LEARNING_MULTIPLIER_MIN)
			throw new LimitException("LearningTimeMultiplier is smaller than "+ScalableApp.LEARNING_MULTIPLIER_MIN+".");
		if (scalingIntervalMultiplier < ScalableApp.SCALING_INTERVAL_MULTIPLIER_MIN) 
			throw new LimitException("ScalingIntervalMultiplier is smaller than "+ScalableApp.SCALING_INTERVAL_MULTIPLIER_MIN+".");
		
		return true;
	}
	
	/**
	 * Creates and stores an {@code ApplicationMetric} out of the current stored {@code ContainerMetrics}.
	 * Furthermore a message will be published on the dedicated topic.
	 * This method does not make any effort in regards to synchronization. Make sure the application is locked before calling it or inconsistencies can occur.
	 * @param app {@linkplain ScalableApp} to its metrics
	 * @param protoProducer {@code ProtobufProducer} to use to publish the message
	 */
	public static void aggregateInstanceMetrics(ScalableApp app, ProtobufProducer protoProducer) {
		
		List<ContainerMetric> containerMetrics = app.getCopyOfInstanceContainerMetricsList();
		List<HttpMetric> httpMetrics = app.getCopyOfHttpMetricsList();
		log.debug("InstanceMetrics: "+ containerMetrics);
		log.debug("Aggregating Instance Metrics for " + app.getIdentifierStringForLogs());
		
		if (containerMetrics.size() == 0) 
			return;
		
		
		long timestamp = 0;
		int requests = 0;
		int latency = 0;
		int cpu = 0;
		long ram = 0;
		int quotient = app.getRequest().getQuotient();
		int instanceCount = app.getCurrentInstanceCount();
		int ramCounter = 0;
		int cpuCounter = 0;
		int latencyCounter = 0;
		
		String metricName = AutoscalerMetric.NAME_APPLICATION;
		String description = "";

		ContainerMetric current = null;
		for (int i = 0; i < containerMetrics.size(); i++) {
			current = containerMetrics.get(i);
			if (!current.isTooOld(app.getMaxMetricAge())) {
				if (current.getCpu() >= 0) {
					cpu += current.getCpu();
					cpuCounter++;
				}
				if (current.getRam() >= 0) {
					ram += current.getRam();
					ramCounter++;
				}
			}
		}
		if (cpuCounter > 0)
			cpu /= cpuCounter;
		if (ramCounter > 0)
			ram /= ramCounter;
		
		timestamp = System.currentTimeMillis();
		
		app.resetContainerMetricsList();

		HttpMetric currentHttp = null;
		for (int i = 0; i < httpMetrics.size(); i++) {
			currentHttp = httpMetrics.get(i);
			if (!currentHttp.isTooOld(app.getMaxMetricAge()) && currentHttp.getRequests() > 0 ) {
				requests += currentHttp.getRequests();
				if (currentHttp.getLatency() >= 0) {
					latency += currentHttp.getLatency();
					latencyCounter++;
				}
			}
		}
		if (latencyCounter > 0) {
			latency /= latencyCounter;
		}
		app.resetHttpMetricList();
		
		if (cpuCounter > 0 && ramCounter > 0) {
			ProtoApplicationMetric applicationMetric = ProtoApplicationMetric.newBuilder()
					.setTimestamp(timestamp)
					.setMetricName(metricName)
					.setAppId(app.getBinding().getResourceId())
					.setCpu(cpu)
					.setRam(ram)
					.setRequests(requests)
					.setLatency(latency)
					.setQuotient(quotient)
					.setInstanceCount(instanceCount)
					.setDescription(description)
					.build();
			
			ApplicationMetric appMetric = new ApplicationMetric(applicationMetric);
			if (protoProducer != null)
				protoProducer.produceApplicationMetric(applicationMetric);
			app.addMetric(appMetric);
			log.debug("New ApplicationMetric: " + appMetric);
			log.debug("ApplicationMetrics: "+ app.getCopyOfApplicationMetricsList());
		}
	}
}
