/*
 * Sonar PL/SQL Plugin (Community)
 * Copyright (C) 2015-2017 Felipe Zorzo
 * mailto:felipebzorzo AT gmail DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plsqlopen;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.sonar.api.batch.fs.FilePredicates;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.rule.CheckFactory;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.cpd.NewCpdTokens;
import org.sonar.api.config.Settings;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.plsqlopen.checks.CheckList;
import org.sonar.plsqlopen.checks.PlSqlCheck;
import org.sonar.plsqlopen.lexer.PlSqlLexer;
import org.sonar.plsqlopen.squid.PlSqlAstScanner;
import org.sonar.plsqlopen.squid.PlSqlConfiguration;
import org.sonar.plsqlopen.squid.ProgressReport;
import org.sonar.plsqlopen.squid.SonarQubePlSqlFile;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.sonar.sslr.api.GenericTokenType;
import com.sonar.sslr.api.Token;
import com.sonar.sslr.impl.Lexer;

public class PlSqlSquidSensor implements Sensor {

    private static final Logger LOG = Loggers.get(PlSqlSquidSensor.class);
    private final PlSqlChecks checks;

    private SonarComponents components;
    private SensorContext context;
    private PlSqlConfiguration configuration;
    
    public PlSqlSquidSensor(CheckFactory checkFactory, SonarComponents components, Settings settings) {
        this(checkFactory, components, settings, null);
    }

    public PlSqlSquidSensor(CheckFactory checkFactory, SonarComponents components, Settings settings,
            @Nullable CustomPlSqlRulesDefinition[] customRulesDefinition) {
        this.checks = PlSqlChecks.createPlSqlCheck(checkFactory)
                .addChecks(CheckList.REPOSITORY_KEY, CheckList.getChecks())
                .addCustomChecks(customRulesDefinition);
        this.components = components;
        this.components.loadMetadataFile(settings.getString(PlSqlPlugin.FORMS_METADATA_KEY));
    }
    
    @Override
    public void describe(SensorDescriptor descriptor) {
        descriptor
            .name("PlsqlSquidSensor")
            .onlyOnLanguage(PlSql.KEY);
    }

    @Override
    public void execute(SensorContext context) {
        this.context = context;
        configuration = new PlSqlConfiguration(context.fileSystem().encoding());
        
        FilePredicates p = context.fileSystem().predicates();
        ArrayList<InputFile> inputFiles = Lists.newArrayList(context.fileSystem().inputFiles(p.and(p.hasType(InputFile.Type.MAIN), p.hasLanguage(PlSql.KEY))));
        
        ProgressReport progressReport = new ProgressReport("Report about progress of code analyzer", TimeUnit.SECONDS.toMillis(10));
        PlSqlAstScanner scanner = new PlSqlAstScanner(context, checks.all(), components);
        
        progressReport.start(inputFiles);
        for (InputFile inputFile : inputFiles) {
            Collection<AnalyzerMessage> issues = scanner.scanFile(inputFile);
            for (AnalyzerMessage analyzerMessage : issues) {
                reportIssue(inputFile, analyzerMessage);
            }
            
            progressReport.nextFile();
        }
        progressReport.stop();
        
        for (InputFile file : inputFiles) {
            saveCpdTokens(file);
        }
    }
    
    @VisibleForTesting
    void reportIssue(InputFile inputFile, AnalyzerMessage message) {
        RuleKey key = checks.ruleKey((PlSqlCheck) message.getCheck());
        PlSqlIssue issue = PlSqlIssue.create(context, key, message.getCost());
        String text = message.getText(Locale.ENGLISH);
        Integer line = message.getLine();
        if (line == null) {
            // either an issue at file or folder level
            issue.setPrimaryLocationOnFile(inputFile, text);
        } else {
            AnalyzerMessage.TextSpan location = message.getLocation();
            if (location != null) {
                int column = message.getLocation().startCharacter;
                int endLine = message.getLocation().endLine;
                int endColumn = message.getLocation().endCharacter;
                
                try {
                    issue.setPrimaryLocation(inputFile, text, line, column, endLine, endColumn);
                } catch (IllegalArgumentException e) {
                    // the previous setPrimaryLocation will fail if it is a multiline token
                    // for now, just fall back to old method
                    LOG.debug("Fail to set primary location", e);
                    issue.setPrimaryLocation(inputFile, text, line, -1, 0, -1);
                }
            } else {
                issue.setPrimaryLocation(inputFile, text, line, -1, 0, -1);
            }
        }
        for (AnalyzerMessage location : message.getSecondaryLocations()) {
            AnalyzerMessage.TextSpan secondarySpan = location.getLocation();
            
            String secondaryText = location.getText(Locale.ENGLISH);
            try {
                issue.addSecondaryLocation(inputFile, secondarySpan.startLine, secondarySpan.startCharacter, secondarySpan.endLine, secondarySpan.endCharacter, secondaryText);
            } catch (IllegalArgumentException e) {
                LOG.debug("addSecondaryLocation FAIL", e);
            }
        }
        issue.save();
    }

    private void saveCpdTokens(InputFile inputFile) {
        NewCpdTokens newCpdTokens = context.newCpdTokens().onFile(inputFile);
        Lexer lexer = PlSqlLexer.create(configuration);
        PlSqlFile plSqlFile = SonarQubePlSqlFile.create(inputFile, context);
        List<Token> tokens = lexer.lex(plSqlFile.content());
        for (Token token : tokens) {
            if (token.getType() == GenericTokenType.EOF) {
                continue;
            }
            TokenLocation location = TokenLocation.from(token);
            newCpdTokens.addToken(location.line(), location.column(), location.endLine(), location.endColumn(), token.getValue());
        }
        newCpdTokens.save();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }

}
