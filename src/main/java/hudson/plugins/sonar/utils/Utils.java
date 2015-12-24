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

import hudson.FilePath;
import hudson.plugins.sonar.template.SonarPomGenerator;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;

public class Utils {

	/**
	 * @note 20101021: Robin Jarry modified this method. now the source dirs
	 *       property supports the wildcards
	 * @param src
	 * @param root
	 * @return
	 * @throws InterruptedException
	 * @throws IOException
	 */
	public static List<String> getProjectSrcDirsList(String src, final FilePath root)
	        throws IOException, InterruptedException
	{
	    final List<String> wildcards = new ArrayList<String>();
	    List<String> sourceDirs = new ArrayList<String>();
	
	    String[] patterns = StringUtils.split(src, ',');
	
	    for (String pat : patterns) {
	        if (pat != null && !pat.trim().isEmpty()) {
	        	String trimmedPattern = pat.trim();
	        	if (trimmedPattern.indexOf("?")==-1 && trimmedPattern.indexOf("*") == -1){
	        		sourceDirs.add(trimmedPattern);
	        	}
	        	else {
	        		wildcards.add(trimmedPattern);
	        	}
	        }
	    }
	
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
	        String srcDir = path.getRemote();
	        srcDir = srcDir.substring(root.getRemote().length());
	        if (srcDir.isEmpty()) {
	            srcDir = ".";
	        } else {
	            while (srcDir.startsWith("/") || srcDir.startsWith("\\")) {
	                srcDir = srcDir.substring(1);
	            }
	        }
	        sourceDirs.add(srcDir);
	    }
	
	    return sourceDirs;
	}

}
