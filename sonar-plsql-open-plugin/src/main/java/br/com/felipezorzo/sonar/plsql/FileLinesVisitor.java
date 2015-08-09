package br.com.felipezorzo.sonar.plsql;

import java.util.List;
import java.util.Set;

import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.measures.CoreMetrics;
import org.sonar.api.measures.FileLinesContext;
import org.sonar.api.measures.FileLinesContextFactory;
import org.sonar.squidbridge.SquidAstVisitor;

import com.google.common.collect.Sets;
import com.sonar.sslr.api.AstAndTokenVisitor;
import com.sonar.sslr.api.AstNode;
import com.sonar.sslr.api.GenericTokenType;
import com.sonar.sslr.api.Grammar;
import com.sonar.sslr.api.Token;
import com.sonar.sslr.api.Trivia;

import br.com.felipezorzo.sonar.plsql.api.PlSqlMetric;

public class FileLinesVisitor extends SquidAstVisitor<Grammar> implements AstAndTokenVisitor {
    private final FileLinesContextFactory fileLinesContextFactory;

    private final Set<Integer> linesOfCode = Sets.newHashSet();
    private final Set<Integer> linesOfComments = Sets.newHashSet();
    private final FileSystem fileSystem;

    public FileLinesVisitor(FileLinesContextFactory fileLinesContextFactory, FileSystem fileSystem) {
        this.fileLinesContextFactory = fileLinesContextFactory;
        this.fileSystem = fileSystem;
    }

    @Override
    public void visitToken(Token token) {
        if (token.getType().equals(GenericTokenType.EOF)) {
            return;
        }

        /* Handle all the lines of the token */
        String[] tokenLines = token.getValue().split("\n", -1);
        for (int line = token.getLine(); line < token.getLine() + tokenLines.length; line++) {
            linesOfCode.add(line);
        }

        List<Trivia> trivias = token.getTrivia();
        for (Trivia trivia : trivias) {
            if (trivia.isComment()) {
                linesOfComments.add(trivia.getToken().getLine());
            }
        }
    }

    @Override
    public void leaveFile(AstNode astNode) {
        InputFile inputFile = fileSystem.inputFile(fileSystem.predicates().is(getContext().getFile()));
        if (inputFile == null){
            throw new IllegalStateException("InputFile is null, but it should not be.");
        }
        FileLinesContext fileLinesContext = fileLinesContextFactory.createFor(inputFile);

        int fileLength = getContext().peekSourceCode().getInt(PlSqlMetric.LINES);
        for (int line = 1; line <= fileLength; line++) {
            fileLinesContext.setIntValue(CoreMetrics.NCLOC_DATA_KEY, line, linesOfCode.contains(line) ? 1 : 0);
            fileLinesContext.setIntValue(CoreMetrics.COMMENT_LINES_DATA_KEY, line, linesOfComments.contains(line) ? 1 : 0);
        }
        fileLinesContext.save();

        linesOfCode.clear();
        linesOfComments.clear();
    }
}