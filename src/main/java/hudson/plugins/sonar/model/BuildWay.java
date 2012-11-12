package hudson.plugins.sonar.model;

import org.kohsuke.stapler.DataBoundConstructor;

public class BuildWay {
	/**
	 * since 1.6.1.thales.3
	 */
	private final String value;
	//private final String javaOpts;
	
	@DataBoundConstructor
	public BuildWay(String value) {
		this.value = value;
	}
	
	public String getValue() {
		if (value == null || value.trim().isEmpty()){
			return LightProjectConfig.DEFAULT_BUILD_WAY;
		}
		return value;
	}

}
