/*
 * Sonar is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Sonar is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Sonar; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */

package hudson.plugins.sonar.model;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

public class BuildWay {
	/**
	 * since 1.6.1.thales.3
	 */
	private final String value;
	
	/**
	 * since 1.6.1.thales.7
	 */
	private final String javaOpts;
	
	@DataBoundConstructor
	public BuildWay(String value, String javaOpts) {
		this.value = value;
		this.javaOpts = javaOpts;
	}
	
	public String getValue() {
		if (value == null || value.trim().isEmpty()){
			return LightProjectConfig.DEFAULT_BUILD_WAY;
		}
		return value;
	}
	
	public String getJavaOpts() {
		return StringUtils.trimToEmpty(javaOpts);
	}

}
