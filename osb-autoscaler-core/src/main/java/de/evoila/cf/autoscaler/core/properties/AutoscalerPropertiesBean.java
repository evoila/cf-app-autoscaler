package de.evoila.cf.autoscaler.core.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

/**
 * A bean for storing properties dedicated to the Autoscaler.
 * Spring fills the fields at the start of the Autoscaler with values out of the properties file.
 * @author Marius Berger
 *
 */
@Service
@ConfigurationProperties(prefix = "scaler")
public class AutoscalerPropertiesBean {

	/**
	 * Maximum count a List storing Metrics is allowed the have.
	 */
	private int maxMetricListSize;
	
	/**
	 * Maximum age a Metric is allowed the have.
	 */
	private long maxMetricAge;
	
	/**
	 * Boolean value, whether to ask the scaling engine for the application name when creating a new binding.
	 */
	private boolean updateAppNameAtBinding;
	
	/**
	 * Number of instances to add or subtract from the instance count when scaling static.
	 */
	private int staticScalingSize;
	
	/**
	 * Constructor for Spring to inject the bean.
	 */
	public AutoscalerPropertiesBean() { }

	public int getMaxMetricListSize() {
		return maxMetricListSize;
	}

	public void setMaxMetricListSize(int maxMetricListSize) {
		this.maxMetricListSize = maxMetricListSize;
	}

	public long getMaxMetricAge() {
		return maxMetricAge;
	}

	public void setMaxMetricAge(long maxMetricAge) {
		this.maxMetricAge = maxMetricAge;
	}

	public boolean isUpdateAppNameAtBinding() {
		return updateAppNameAtBinding;
	}

	public void setUpdateAppNameAtBinding(boolean updateAppNameAtBinding) {
		this.updateAppNameAtBinding = updateAppNameAtBinding;
	}

	public int getStaticScalingSize() {
		return staticScalingSize;
	}

	public void setStaticScalingSize(int staticScalingSize) {
		if (staticScalingSize > 0) {
			this.staticScalingSize = staticScalingSize;
		} else {
			this.staticScalingSize = 1;
		}
	}
}
