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
package hudson.plugins.sonar.template;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.plugins.sonar.SonarPublisher;
import hudson.plugins.sonar.model.LightProjectConfig;
import hudson.plugins.sonar.model.ReportsConfig;
import hudson.plugins.sonar.utils.Utils;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.StringUtils;

/**
 * @author Evgeny Mandrikov
 * @since 1.2
 */
public final class SonarPomGenerator {
	public static void generatePomForNonMavenProject(LightProjectConfig project, FilePath root, String pomName, EnvVars env, boolean isSonarFourOrHigher) throws IOException, InterruptedException {
		SimpleTemplate pomTemplate = new SimpleTemplate("hudson/plugins/sonar/sonar-light-pom.template");
		pomTemplate.setAttribute("groupId", SonarPublisher.expandJenkinsVars(env,project.getGroupId()));
		//pomTemplate.setAttribute("artifactId", project.getArtifactId());
		pomTemplate.setAttribute("artifactId", SonarPublisher.expandJenkinsVars(env, project.getArtifactId()));
		pomTemplate.setAttribute("projectName", SonarPublisher.expandJenkinsVars(env, project.getProjectName()));
		//pomTemplate.setAttribute("projectName", project.getProjectName());  // FIXME
		// Godin:
		// env.expand
		// because
		// projectName
		// can
		// be
		// "${JOB_NAME}"
		//pomTemplate.setAttribute("projectVersion", StringUtils.isEmpty(project.getProjectVersion()) ? "1.0" : project
		//							.getProjectVersion());
		pomTemplate.setAttribute("projectVersion", StringUtils.isEmpty(project.getProjectVersion()) ? "1.0" : SonarPublisher.expandJenkinsVars(env, project
				.getProjectVersion()));
		pomTemplate.setAttribute("javaVersion", StringUtils.isEmpty(project.getJavaVersion()) ? "1.5" : SonarPublisher.expandJenkinsVars(env,project.getJavaVersion()));
		pomTemplate.setAttribute("compilerVersion", StringUtils.isEmpty(project.getCompilerVersion()) ? "" : SonarPublisher.expandJenkinsVars(env,project.getCompilerVersion()));

		List<String> srcDirs = Utils.getProjectSrcDirsList(project.getProjectSrcDir(), root, env);
		boolean multiSources = srcDirs.size() > 1;
		setPomElement("sourceDirectory", srcDirs.size() == 0 ? "src" : srcDirs.get(0), pomTemplate);
		pomTemplate.setAttribute("srcDirsPlugin", multiSources ? generateSrcDirsPluginTemplate(srcDirs).toString() : "");

		setPomElement("project.build.sourceEncoding", SonarPublisher.expandJenkinsVars(env,project.getProjectSrcEncoding()), pomTemplate);
		setPomElement("encoding", SonarPublisher.expandJenkinsVars(env,project.getProjectSrcEncoding()), pomTemplate);
		//setPomElement("description", project.getProjectDescription(), pomTemplate);
		setPomElement("description", SonarPublisher.expandJenkinsVars(env, project.getProjectDescription()), pomTemplate);
		setPomElement("outputDirectory", SonarPublisher.expandJenkinsVars(env,project.getProjectBinDir()), pomTemplate);

		setPomElement("sonar.language", project.getLanguage(), pomTemplate);

		ReportsConfig reports = project.isReuseReports() ? project.getReports()
				: new ReportsConfig();
		if (!isSonarFourOrHigher){
			setPomElement("sonar.phase", multiSources ? "generate-sources" : "", pomTemplate);
			setPomElement("sonar.dynamicAnalysis", project.isReuseReports() ? "reuseReports" : "false",
					true, pomTemplate);
			setPomElement("sonar.sources", "", pomTemplate);
		}
		else {
			setPomElement("sonar.phase", "", pomTemplate);
			setPomElement("sonar.dynamicAnalysis", "",pomTemplate);
			setPomElement("sonar.sources", generateSonarSources(srcDirs), pomTemplate);
		}
		setPomElement("sonar.surefire.reportsPath", reports.getSurefireReportsPath(), project
				.isReuseReports(), pomTemplate);
		setPomElement("sonar.cobertura.reportPath", reports.getCoberturaReportPath(), project
				.isReuseReports(), pomTemplate);
		setPomElement("sonar.clover.reportPath", reports.getCloverReportPath(), project
				.isReuseReports(), pomTemplate);

		pomTemplate.write(root, pomName);
	}

	private static SimpleTemplate generateSrcDirsPluginTemplate(List<String> srcDirs)
	throws IOException, InterruptedException
	{
		SimpleTemplate srcTemplate = new SimpleTemplate(
		"hudson/plugins/sonar/sonar-multi-sources.template");
		StringBuffer sourcesXml = new StringBuffer();
		for (int i = 1; i < srcDirs.size(); i++) {
			sourcesXml.append("<source><![CDATA[").append(StringUtils.trim(srcDirs.get(i))).append(
			"]]></source>\n");
		}
		srcTemplate.setAttribute("sources", sourcesXml.toString());
		return srcTemplate;
	}
	
	private static String generateSonarSources(List<String> srcDirs){
		StringBuilder sb = new StringBuilder();
		for (String sourceDir : srcDirs){
			sb.append(sourceDir).append(',');
		}
		sb.setLength(sb.length()-1);
		return sb.toString();
	}

	private static void setPomElement(String tagName, String tagValue, SimpleTemplate template) {
		setPomElement(tagName, tagValue, true, template);
	}

	private static void setPomElement(String tagName, String tagValue, boolean enabled,
			SimpleTemplate template)
	{
		String tagContent;
		if (enabled && StringUtils.isNotBlank(tagValue)) {
			tagContent = "<" + tagName + "><![CDATA[" + tagValue + "]]></" + tagName + ">";
		} else {
			tagContent = "";
		}
		template.setAttribute(tagName, tagContent);
	}

	public static List<FilePath> listFiles(FilePath directory, FileFilter filter)
	throws IOException, InterruptedException
	{
		// List of files / directories
		List<FilePath> files = new ArrayList<FilePath>();

		// Get files / directories in the directory
		List<FilePath> entries = directory.listDirectories();

		// Go over entries
		//AM : Sometimes listDirectories returns null
		if (entries != null){
			for (FilePath entry : entries) {

				// If there is no filter or the filter accepts the
				// file / directory, add it to the list
				if (filter.accept(new File(entry.getRemote()))) {
					files.add(entry);
				}

				// If the file is a directory and the recurse flag
				// is set, recurse into the directory
				files.addAll(listFiles(entry, filter));
			}
		}

		// Return collection of files
		return files;
	}

	/**
	 * Hide utility-class constructor.
	 */
	private SonarPomGenerator() {
	}
}
