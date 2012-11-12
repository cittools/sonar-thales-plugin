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

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * @author Evgeny Mandrikov
 * @since 1.2
 */
public class ReportsConfig {
  /**
   * Optional.
   */
  private final String surefireReportsPath;

  /**
   * Optional.
   */
  private final String coberturaReportPath;

  /**
   * Optional.
   */
  private final String cloverReportPath;
  
  /**
   * Used with TUSARNotifier plugin.
   * When checked, this option insert TUSAR parameters String into Maven Options
   */
  private final Boolean useTusarReports;
    
  public ReportsConfig() {
    this(null, null, null, false);
  }

  @DataBoundConstructor
  public ReportsConfig(String surefireReportsPath, String coberturaReportPath, String cloverReportPath, Boolean useTusarReports) {
    this.surefireReportsPath = surefireReportsPath;
    this.coberturaReportPath = coberturaReportPath;
    this.cloverReportPath = cloverReportPath;
    this.useTusarReports = useTusarReports;
  }

  public String getSurefireReportsPath() {
    return surefireReportsPath;
  }

  public String getCoberturaReportPath() {
    return coberturaReportPath;
  }

  public String getCloverReportPath() {
    return cloverReportPath;
  }
  
  /**
   * @return true, if we should use TUSAR Reports
   */
  public boolean isUseTusarReports() {
    return useTusarReports!=null?useTusarReports:false;
  }
}
