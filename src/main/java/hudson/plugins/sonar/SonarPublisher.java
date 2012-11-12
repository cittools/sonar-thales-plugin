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
package hudson.plugins.sonar;

import hudson.CopyOnWrite;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.maven.AbstractMavenProject;
import hudson.maven.MavenModule;
import hudson.maven.MavenModuleSet;
import hudson.maven.ModuleName;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Hudson;
import hudson.model.Result;
import hudson.plugins.sonar.model.LightProjectConfig;
import hudson.plugins.sonar.model.TriggersConfig;
import hudson.plugins.sonar.template.SonarPomGenerator;
import hudson.plugins.sonar.utils.MagicNames;
import hudson.plugins.sonar.utils.SonarMaven;
import hudson.plugins.sonar.utils.Utils;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Maven;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.tasks.Maven.MavenInstallation;
import hudson.util.FormValidation;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.logging.Logger;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Old fields should be left so that old config data can be read in, but
 * they should be deprecated and transient so that they won't show up in XML
 * when writing back
 */
public class SonarPublisher extends Notifier {
	private static final Logger LOG = Logger.getLogger(SonarPublisher.class.getName());

	/**
	 * Store a config version, so we're able to migrate config on various
	 * functionality upgrades.
	 */
	private Integer configVersion;

	/**
	 * Sonar installation name.
	 */
	private final String installationName;

	/**
	 * Optional.
	 * 
	 * @since 1.4
	 */
	private String branch;

	/**
	 * Optional.
	 * 
	 * @since 1.6
	 */
	private String language;

	/**
	 * Optional.
	 */
	private final String mavenOpts;

	/**
	 * Optional.
	 */
	private final String jobAdditionalProperties;

	/**
	 * Triggers. If null, then we should use triggers from {@link SonarInstallation}.
	 * 
	 * @since 1.2
	 */
	private TriggersConfig triggers;

	// =================================================
	// Next fields available only for free-style projects

	private String mavenInstallationName;

	/**
	 * @since 1.2
	 */
	private String rootPom;

	/**
	 * If not null, then we should generate pom.xml.
	 * 
	 * @since 1.2
	 */
	private LightProjectConfig lightProject;


	public SonarPublisher(String installationName, String jobAdditionalProperties, String mavenOpts) {
		this(installationName, new TriggersConfig(), jobAdditionalProperties, mavenOpts, null, null, null);
	}

	public SonarPublisher(
			String installationName,
			TriggersConfig triggers,
			String jobAdditionalProperties, String mavenOpts) {
		this(installationName, triggers, jobAdditionalProperties, mavenOpts, null, null, null);
	}

	public SonarPublisher(String installationName,
			TriggersConfig triggers,
			String jobAdditionalProperties, String mavenOpts,
			String mavenInstallationName, String rootPom,
			LightProjectConfig lightProject) {
		this(installationName, null, null, triggers, jobAdditionalProperties, mavenOpts, mavenInstallationName, rootPom, lightProject);
	}

	@DataBoundConstructor
	public SonarPublisher(String installationName,
			String branch,
			String language,
			TriggersConfig triggers,
			String jobAdditionalProperties, String mavenOpts,
			String mavenInstallationName, String rootPom,
			LightProjectConfig lightProject) {
		super();
		this.configVersion = 1;
		this.installationName = installationName;
		this.branch = branch;
		this.language = language;
		// Triggers
		this.triggers = triggers;
		// Maven
		this.mavenOpts = mavenOpts;
		this.jobAdditionalProperties = jobAdditionalProperties;
		// Non Maven Project
		this.mavenInstallationName = mavenInstallationName;
		this.rootPom = rootPom;
		// Sonar Light
		this.lightProject = lightProject;
	}

	/**
	 * Migrate data.
	 * 
	 * @return this
	 */
	@SuppressWarnings({ "UnusedDeclaration" })
	public Object readResolve() {
		// Default unspecified to v0
		if (configVersion == null) {
			configVersion = 0;
		}
		if (configVersion < 1) {
			// No migration - see http://jira.codehaus.org/browse/SONARPLUGINS-402
			configVersion = 1;
		}
		return this;
	}

	/**
	 * @return config version
	 */
	public Integer getConfigVersion() {
		return configVersion;
	}

	/**
	 * @return name of {@link hudson.plugins.sonar.SonarInstallation}
	 */
	public String getInstallationName() {
		return installationName;
	}

	/**
	 * @return MAVEN_OPTS
	 */
	public String getMavenOpts() {
		return mavenOpts;
	}

	/**
	 * @return additional Maven options like "-Pprofile" and "-Dname=value"
	 */
	public String getJobAdditionalProperties() {
		return StringUtils.trimToEmpty(jobAdditionalProperties);
	}

	/**
	 * @return true, if we should use triggers from {@link SonarInstallation}
	 */
	public boolean isUseGlobalTriggers() {
		return triggers == null;
	}

	public boolean isUseLocalTriggers() {
		return !isUseGlobalTriggers();
	}

	/**
	 * See <a href="http://docs.codehaus.org/display/SONAR/Advanced+parameters#Advancedparameters-ManageSCMbranches">Sonar Branch option</a>.
	 * 
	 * @return branch
	 * @since 1.4
	 */
	public String getBranch() {
		return branch;
	}

	public String getLanguage() {
		return StringUtils.trimToEmpty(language);
	}

	/**
	 * @return triggers configuration
	 */
	public TriggersConfig getTriggers() {
		return triggers;
	}

	/**
	 * @return name of {@link hudson.tasks.Maven.MavenInstallation}
	 */
	public String getMavenInstallationName() {
		return mavenInstallationName;
	}

	/**
	 * Root POM. Should be applied only for free-style projects.
	 * 
	 * @return Root POM
	 */
	public String getRootPom() {
		return StringUtils.trimToEmpty(rootPom);
	}

	/**
	 * @return true, if we should generate pom.xml
	 */
	public boolean isUseSonarLight() {
		return lightProject != null;
	}

	/**
	 * @return configuration for Sonar Light
	 */
	public LightProjectConfig getLightProject() {
		return lightProject;
	}

	@SuppressWarnings({ "UnusedDeclaration" })
	public static boolean isMavenBuilder(AbstractProject currentProject) {
		return currentProject instanceof MavenModuleSet;
	}

	public SonarInstallation getInstallation() {
		DescriptorImpl sonarDescriptor = Hudson.getInstance().getDescriptorByType(DescriptorImpl.class);
		if (StringUtils.isEmpty(getInstallationName()) && sonarDescriptor.getInstallations().length > 0) {
			return sonarDescriptor.getInstallations()[0];
		}
		for (SonarInstallation si : sonarDescriptor.getInstallations()) {
			if (StringUtils.equals(getInstallationName(), si.getName())) {
				return si;
			}
		}
		return null;
	}

	private boolean isSkip(AbstractBuild<?, ?> build, BuildListener listener, SonarInstallation sonarInstallation) {
		final String skipLaunchMsg;
		if (sonarInstallation == null) {
			skipLaunchMsg = Messages.SonarPublisher_NoInstallation(getInstallationName(),
					Hudson.getInstance().getDescriptorByType(DescriptorImpl.class).getInstallations().length);
		} else if (sonarInstallation.isDisabled()) {
			skipLaunchMsg = Messages.SonarPublisher_InstallDisabled(sonarInstallation.getName());
		} else if (isUseGlobalTriggers()) {
			skipLaunchMsg = sonarInstallation.getTriggers().isSkipSonar(build);
		} else {
			skipLaunchMsg = getTriggers().isSkipSonar(build);
		}
		if (skipLaunchMsg != null) {
			listener.getLogger().println(skipLaunchMsg);
			return true;
		}
		return false;
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {
		final SonarInstallation sonarInstallation = getInstallation();
		if (isSkip(build, listener, sonarInstallation)) {
			return true;
		}
		build.addAction(new BuildSonarAction());

		boolean sonarSuccess = false;
		LightProjectConfig lightProjectConfig = getLightProject();
		if (lightProjectConfig == null || lightProjectConfig.getBuildWay()==null){
			sonarSuccess = executeSonarMaven(build, launcher, listener, sonarInstallation);
		}
		else {
			//TODO : refactor
			String buildWayValue = lightProjectConfig.getBuildWay().getValue();
			if (buildWayValue.equals(LightProjectConfig.MAVEN)){
				sonarSuccess = executeSonarMaven(build, launcher, listener, sonarInstallation);
			}
			else if (buildWayValue.equals(LightProjectConfig.JAVA_RUNNER)){
				try {
					String javaVersion = lightProjectConfig.getJavaVersion().isEmpty()?"1.5":lightProjectConfig.getJavaVersion();

					//Properties for the java runner
					StringBuilder propertiesStringBuilder = new StringBuilder()
					.append("sonar.projectKey=").append(lightProjectConfig.getGroupId()).append(":").append(lightProjectConfig.getArtifactId()).append("\n")
					.append("sonar.projectName=").append(lightProjectConfig.getProjectName()).append("\n")
					.append("sonar.projectVersion=").append(lightProjectConfig.getProjectVersion().isEmpty()?"1.0":lightProjectConfig.getProjectVersion()).append("\n")
					.append("sonar.java.source=").append(javaVersion).append("\n")
					.append("sonar.java.target=").append(javaVersion).append("\n");

					//Source directories
					propertiesStringBuilder.append("sources=");
					List<String> filePaths = Utils.getProjectSrcDirsList(lightProjectConfig.getProjectSrcDir(), build.getWorkspace());
					if (!filePaths.isEmpty()){
						for (String filePath : filePaths){
							propertiesStringBuilder.append(filePath.replaceAll("\\\\", "/")).append(",");
						}
						propertiesStringBuilder.setLength(propertiesStringBuilder.length()-1);
					}
					propertiesStringBuilder.append("\n");


					//Binaries directory
					if (!lightProjectConfig.getProjectBinDir().isEmpty()) {
						propertiesStringBuilder.append("binaries=").append(lightProjectConfig.getProjectBinDir()).append("\n");
					}

					//Description
					if (!lightProjectConfig.getProjectDescription().isEmpty()){
						propertiesStringBuilder.append("description=").append(lightProjectConfig.getProjectDescription()).append("\n");
					}
					
					//Source encoding
					if (!lightProjectConfig.getProjectSrcEncoding().isEmpty()){
						propertiesStringBuilder.append("sonar.sourceEncoding=").append(lightProjectConfig.getProjectSrcEncoding()).append("\n");
					}
					

					//Reuse report
					if (lightProjectConfig.isReuseReports()){
						propertiesStringBuilder.append("sonar.dynamicAnalysis=reuseReports\n");
						if (lightProjectConfig.getReports().isUseTusarReports()){
							propertiesStringBuilder.append("sonar.language=tusar\n").append("sonar.tusar.reportsPaths=generatedDTKITFiles/COVERAGE;generatedDTKITFiles/MEASURES;generatedDTKITFiles/VIOLATIONS;generatedDTKITFiles/TESTS\n");
						}
						else {
							if (lightProjectConfig.getReports().getCloverReportPath()!= null && !lightProjectConfig.getReports().getCloverReportPath().isEmpty()){
								propertiesStringBuilder.append("sonar.clover.reportsPath=").append(lightProjectConfig.getReports().getCloverReportPath()).append("\n");
							}
							if (lightProjectConfig.getReports().getCoberturaReportPath()!= null && !lightProjectConfig.getReports().getCoberturaReportPath().isEmpty()){
								propertiesStringBuilder.append("sonar.cobertura.reportsPath=").append(lightProjectConfig.getReports().getCoberturaReportPath()).append("\n");
							}
							if (lightProjectConfig.getReports().getSurefireReportsPath()!= null && !lightProjectConfig.getReports().getSurefireReportsPath().isEmpty()){
								propertiesStringBuilder.append("sonar.surefire.reportsPath=").append(lightProjectConfig.getReports().getSurefireReportsPath()).append("\n");
							}
						}
					}
					if (jobAdditionalProperties!=null && !jobAdditionalProperties.isEmpty()){
						/*String modifiedJobAdditionalProperties = jobAdditionalProperties;
						if (modifiedJobAdditionalProperties.startsWith("-D")){
							modifiedJobAdditionalProperties = modifiedJobAdditionalProperties.substring(2);
						}
						propertiesStringBuilder.append(modifiedJobAdditionalProperties).append("\n");*/
						
						for (String modifiedJobAdditionalProperties : jobAdditionalProperties.split("-D")){
							propertiesStringBuilder.append(modifiedJobAdditionalProperties.trim()).append("\n");
						}
					}
					String properties = propertiesStringBuilder.toString();
					sonarSuccess = executeSonarJavaRunner(build, launcher, listener, sonarInstallation,properties);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		if (!sonarSuccess) {
			// returning false has no effect on the global build status so need to do it manually
			build.setResult(Result.FAILURE);
		}
		LOG.info("Sonar build completed: " + build.getResult());
		return sonarSuccess;
	}

	private boolean executeSonarJavaRunner(AbstractBuild<?, ?> build,
			Launcher launcher, BuildListener listener,
			SonarInstallation sonarInstallation, String properties) throws IOException, InterruptedException {
		SonarRunner sonarRunner = new SonarRunner(build.getProject(), launcher, build.getEnvironment(listener), build.getWorkspace());
		return sonarRunner.launch(listener, getInstallation(), lightProject.getJavaOpts(), properties) == 0;
	}

	public MavenModuleSet getMavenProject(AbstractBuild build) {
		return (build.getProject() instanceof MavenModuleSet) ? (MavenModuleSet) build.getProject() : null;
	}

	private String getPomName(AbstractBuild<?, ?> build) {
		String pomName;
		MavenModuleSet mavenModuleProject = getMavenProject(build);
		if (mavenModuleProject != null) {
			pomName = mavenModuleProject.getRootPOM();
		} else {
			pomName = getRootPom();
		}
		if (StringUtils.isEmpty(pomName)) {
			pomName = "pom.xml";
		}
		return pomName;
	}

	private String getPomName(AbstractBuild<?, ?> build, BuildListener listener) throws IOException, InterruptedException {
		String pomName = getPomName(build);
		// TODO Godin: why we should expand it?
		// Expand, because pomName can be "${VAR}/pom.xml"
		EnvVars env = build.getEnvironment(listener);
		pomName = env.expand(pomName);
		return pomName;
	}

	private boolean executeSonarMaven(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener, SonarInstallation sonarInstallation) {
		try {
			String pomName = getPomName(build, listener);
			FilePath root = build.getWorkspace();
			if (isUseSonarLight()) {
				LOG.info("Generating " + pomName);
				SonarPomGenerator.generatePomForNonMavenProject(getLightProject(), root, pomName);
			}
			String mavenInstallationName = getMavenInstallationName();
			if (isMavenBuilder(build.getProject())) {
				MavenModuleSet mavenModuleSet = getMavenProject(build);
				if (null != mavenModuleSet.getMaven().getName()) {
					mavenInstallationName = mavenModuleSet.getMaven().getName();
				}
			}

			// Execute maven
			return SonarMaven.executeMaven(build, launcher, listener, mavenInstallationName, pomName, sonarInstallation, this);
		} catch (IOException e) {
			Util.displayIOException(e, listener);
			e.printStackTrace(listener.fatalError("command execution failed"));
			return false;
		} catch (InterruptedException e) {
			return false;
		} catch (Exception e) {
			e.printStackTrace(listener.fatalError("command execution failed"));
			return false;
		}
	}

	protected String getSonarUrl(AbstractProject<?, ?> project) {
		SonarInstallation sonarInstallation = getInstallation();
		if (sonarInstallation == null) {
			return null;
		}
		String url = sonarInstallation.getServerLink();
		if (project instanceof AbstractMavenProject) {
			// Maven Project
			AbstractMavenProject mavenProject = (AbstractMavenProject) project;
			if (mavenProject.getRootProject() instanceof MavenModuleSet) {
				MavenModuleSet mms = (MavenModuleSet) mavenProject.getRootProject();
				MavenModule rootModule = mms.getRootModule();
				if (rootModule != null) {
					ModuleName moduleName = rootModule.getModuleName();
					url = sonarInstallation.getProjectLink(moduleName.groupId, moduleName.artifactId, getBranch());
				}
			}
		}
		/**
		 * Free-style job:
		 * If project was built by maven, then pom.xml already exists
		 * If project wasn't built by maven, then there is should be generated pom.xml
		 */
		try {
			AbstractBuild<?, ?> lastBuild = project.getLastBuild();
			if (this.getLightProject() != null){
    			url = sonarInstallation.getProjectLink(this.lightProject.getGroupId(), 
    			                                       this.lightProject.getArtifactId(), 
    			                                       this.branch);
    			
			}else{
        		if (lastBuild != null) {
    				MavenXpp3Reader reader = new MavenXpp3Reader();
    				Model model = reader.read(new InputStreamReader(lastBuild.getWorkspace().child(getPomName(lastBuild)).read()));
    				String groupId = model.getGroupId();
    				String artifactId = model.getArtifactId();
    				url = sonarInstallation.getProjectLink(groupId, artifactId, getBranch());
    				}
			}
		} catch (IOException e) {
			// ignore
		} catch (XmlPullParserException e) {
			// ignore
		} catch (NullPointerException e) {
			// ignore something in the line can be null for maven project
			// Model model = reader.read(new InputStreamReader(lastBuild.getWorkspace().child(getPomName(lastBuild)).read()));
		}
		return url;
	}

	@Override
	public Action getProjectAction(AbstractProject<?, ?> project) {
		return new ProjectSonarAction(getSonarUrl(project));
	}

	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.BUILD;
	}


	@Extension(ordinal = 1000)
	public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

		@CopyOnWrite
		private volatile SonarInstallation[] installations = new SonarInstallation[0]; // NOSONAR

		public DescriptorImpl() {
			super();
			load();
		}


		@Override
		public String getDisplayName() {
			return "Sonar";
		}

		@Override
		public String getHelpFile() {
			return MagicNames.PLUGIN_HOME + "/help.html";
		}


		/**
		 * @return all configured {@link hudson.plugins.sonar.SonarInstallation}
		 */
		public SonarInstallation[] getInstallations() {
			return installations;
		}

		public void setInstallations(SonarInstallation... installations) {
			this.installations = installations;
			save();
		}
		
		/**
		 * This method is used in UI, so signature and location of this method is important (see SONARPLUGINS-1337).
		 * 
		 * @return all configured {@link hudson.tasks.Maven.MavenInstallation}
		 */
		public MavenInstallation[] getMavenInstallations() {

			return Hudson.getInstance().getDescriptorByType(Maven.DescriptorImpl.class).getInstallations();

		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject json) {
			List<SonarInstallation> list = req.bindJSONToList(SonarInstallation.class, json.get("inst"));
			setInstallations(list.toArray(new SonarInstallation[list.size()]));
			return true;
		}

		@SuppressWarnings({ "UnusedDeclaration", "ThrowableResultOfMethodCallIgnored" })
		public FormValidation doCheckMandatory(@QueryParameter String value) {
			return StringUtils.isBlank(value) ?
					FormValidation.error(Messages.SonarPublisher_MandatoryProperty()) : FormValidation.ok();
		}

		@SuppressWarnings({ "UnusedDeclaration", "ThrowableResultOfMethodCallIgnored" })
		public FormValidation doCheckMandatoryAndNoSpaces(@QueryParameter String value) {
			return (StringUtils.isBlank(value) || value.contains(" ")) ?
					FormValidation.error(Messages.SonarPublisher_MandatoryPropertySpaces()) : FormValidation.ok();
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			// eventually check if job type of FreeStyleProject.class || MavenModuleSet.class
			return true;
		}
	}
}
