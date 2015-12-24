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

package hudson.plugins.sonar.utils;

import hudson.EnvVars;
import hudson.FilePath;
import hudson.plugins.sonar.SonarPublisher;
import hudson.plugins.sonar.template.SonarPomGenerator;
import hudson.remoting.VirtualChannel;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;

public class Utils {

	private static String STAR_WILDCARD = "*";
	private static String INTEROGATION_WILDCARD = "?";


	/**
	 * @note 20101021: Robin Jarry modified this method. now the source dirs
	 *       property supports the wildcards
	 * @param src
	 * @param root
	 * @return
	 * @throws InterruptedException
	 * @throws IOException
	 */
	public static List<String> getProjectSrcDirsList(String src, final FilePath root, EnvVars env)
	throws IOException, InterruptedException
	{
		final List<String> wildcards = new ArrayList<String>();
		List<String> sourceDirs = new ArrayList<String>();
		final Map<FilePath, String> pathsOutsideWorkspace = new HashMap<FilePath, String>();

		String[] patterns = StringUtils.split(src, ',');

		for (String pat : patterns) {
			if (pat != null && !pat.trim().isEmpty()) {
				String trimmedPattern = pat.trim();
				if (trimmedPattern.indexOf("?")==-1 && trimmedPattern.indexOf("*") == -1){
					sourceDirs.add(SonarPublisher.expandJenkinsVars(env, trimmedPattern) );
				}
				else {
					//prod00139875: Manage wildcard outside the workspace
					FilePath absolutePath = getPathFromOutsideWorkspacePattern(trimmedPattern, root.getChannel());
					if (absolutePath == null){
						wildcards.add(SonarPublisher.expandJenkinsVars(env, trimmedPattern));
					}
					else {
						pathsOutsideWorkspace.put(absolutePath, trimmedPattern);
					}
				}
			}
		}

		if (!wildcards.isEmpty()){

			List<FilePath> paths = SonarPomGenerator.listFiles(root, new FileFilter() {
				@Override
				public boolean accept(File pathname) {
					for (String pattern : wildcards) {
						if (FilenameUtils.wildcardMatch(pathname.getPath().replace('\\', '/'), pattern.replace('\\', '/'))){
							return true;
						}
						//TODO : Tmp fix : try to add the workspace to the pattern for the case : folder/*/anotherFolder
						else {
							String modifiedPattern = root.getRemote()+"/";
							if (modifiedPattern.endsWith("/") || modifiedPattern.endsWith("\\")){
								modifiedPattern = modifiedPattern.substring(0, modifiedPattern.length()-1);
							}
							modifiedPattern = modifiedPattern + "/"+ pattern;
							if (FilenameUtils.wildcardMatch(pathname.getPath().replace('\\', '/'), modifiedPattern.replace('\\', '/'))){
								return true;
							}
						}
					}
					return false;
				}
			});
			for (FilePath path : paths) {
				sourceDirs.add(path.getRemote());
			}

		}

		//prod00139875: Manage wildcards outside the workspace
		if (!pathsOutsideWorkspace.isEmpty()){
			for (final FilePath filePath : pathsOutsideWorkspace.keySet()){
				List<FilePath> paths = SonarPomGenerator.listFiles(filePath, new FileFilter() {
					@Override
					public boolean accept(File pathname) {
						if (FilenameUtils.wildcardMatch(pathname.getPath().replace('\\', '/'), pathsOutsideWorkspace.get(filePath).replace('\\', '/'))){
							return true;
						}
						return false;
					}
				});
				for (FilePath path : paths) {
					sourceDirs.add(path.getRemote());
				}
			}
		}


		return sourceDirs;
	}

	private static FilePath getPathFromOutsideWorkspacePattern(String pattern,VirtualChannel channel){

		//Algorithm: find the first index of ? or * and check if the path before this index is absolute or not
		String modifiedPattern = pattern.replace('\\', '/');
		int interrogationIndex = modifiedPattern.indexOf(INTEROGATION_WILDCARD);
		int starIndex = modifiedPattern.indexOf(STAR_WILDCARD);

		int firstWildcardIndex = -1;

		if (interrogationIndex == -1 && starIndex == -1){
			return null;
		}

		if (interrogationIndex ==-1){
			firstWildcardIndex = starIndex;
		}
		else if (starIndex ==-1){
			firstWildcardIndex = interrogationIndex;
		}
		else {
			firstWildcardIndex = interrogationIndex < starIndex ? interrogationIndex : starIndex;
		}

		if (firstWildcardIndex == 0){
			//Pattern example: **/src/main/java
			return null;
		}

		String pathInPattern = modifiedPattern.substring(0,firstWildcardIndex);
		int lastSlashIndex = pathInPattern.lastIndexOf("/");

		if (lastSlashIndex == -1){
			return null;
		}

		pathInPattern = pathInPattern.substring(0,lastSlashIndex+1);
		FilePath tmpFilePath = new FilePath(channel, pathInPattern);
		try {
			String absolutePathTmpFilePath = tmpFilePath.absolutize().getRemote();
			System.out.println(absolutePathTmpFilePath + " try " +pathInPattern);
			//Root directory
			if (pathInPattern.equals(absolutePathTmpFilePath.replace('\\', '/'))){
				return tmpFilePath;
			}
			//other directories
			else if (pathInPattern.equals(new String(absolutePathTmpFilePath+'/').replace('\\', '/'))){
				return tmpFilePath;
			}

		} catch (IOException e) {
			System.out.println(" catch " +pathInPattern);
			e.printStackTrace();
			return null;
		} catch (InterruptedException e) {
			e.printStackTrace();
			return null;
		}

		return null;

	}

}
