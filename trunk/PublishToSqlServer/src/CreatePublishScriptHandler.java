import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VirtualDirectoryImpl;
import com.jetbrains.rider.projectView.solutionExplorer.SolutionExplorerNodeRider;
import net.pempek.unicode.UnicodeBOMInputStream;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.text.ParseException;
import java.util.*;

public class CreatePublishScriptHandler extends AnAction {

    private final String dbo = "dbo";
    private String publishFailedTitle = "Create Publish Script Failed";
    ;

    // If you register the action from Java code, this constructor is used to set the menu item name
    // (optionally, you can specify the menu description and an icon to display next to the menu item).
    // You can omit this constructor when registering the action in the plugin.xml file.
    public CreatePublishScriptHandler() {
        // Set the menu item name.
        super("Create _Publish _Script _Handler");
        // Set the menu item name, description and icon.
        // super("Text _Boxes","Item description",IconLoader.getIcon("/Mypackage/icon.png"));
    }

    public void actionPerformed(AnActionEvent event) {
        final VirtualDirectoryImpl folder = getDatabaseFolder(event);
        List<SQLFile> sqlFiles = getSQLFiles(folder);
        List<SQLFile> modifiedSQLFiles = getSqlFilesUpdated(sqlFiles);
        SQLFile publishScript = createPublishScript(modifiedSQLFiles);
        VirtualFile publishScriptFile = saveSqlFile(publishScript, getPublishScriptLocation(event).getAbsolutePath() + "/publishScript.sql");

        FileEditorManager manager = FileEditorManager.getInstance(event.getProject());
        manager.openFile(publishScriptFile, true);
    }

    private List<SQLFile> getSqlFilesUpdated(List<SQLFile> sqlFiles) {
        try {
            return ParseSqlFilesToUpdate(sqlFiles);
        } catch (ParseException e) {
            Messages.showErrorDialog("Could not parse sql file", publishFailedTitle);
            return null;
        }
    }

    private VirtualFile saveSqlFile(SQLFile publishScript, String location) {
        try {
            PrintWriter writer = new PrintWriter(location, "Unicode");
            for (String line : publishScript.getSqlContent()) {
                writer.println(line);
            }
            writer.close();

            return LocalFileSystem.getInstance().findFileByPath(location);
        } catch (IOException e) {
            Messages.showErrorDialog("Could not save publish script", publishFailedTitle);
            return null;
        }
    }

    private SQLFile createPublishScript(List<SQLFile> modifiedSQLFiles) {
        List<String> sqlStatements = new ArrayList<>();

        for (SQLFile sqlFile : modifiedSQLFiles) {
            sqlStatements.addAll(sqlFile.getSqlContent());
        }
        return new SQLFile(sqlStatements);
    }

    private List<SQLFile> ParseSqlFilesToUpdate(List<SQLFile> sqlFiles) throws ParseException {
        List<SQLFile> edditedtSqlFiles = new ArrayList<>();

        for (SQLFile sqlFile : sqlFiles) {
            String firstLine = sqlFile.getSqlContent().get(0);
            if (firstLine.contains("CREATE PROCEDURE")) {
                SQLFile edditedSqlFile = replaceProcedureUpdate(sqlFile);
                edditedtSqlFiles.add(edditedSqlFile);
            } else if (firstLine.contains("CREATE TABLE")) {
                SQLFile edditedSqlFile = replaceTableUpdate(sqlFile);
                edditedtSqlFiles.add(edditedSqlFile);
            }
        }
        return edditedtSqlFiles;
    }

    private SQLFile replaceTableUpdate(SQLFile sqlFile) {
        return replaceCreateWithUpdate(sqlFile, "TABLE");
    }

    private SQLFile replaceProcedureUpdate(SQLFile sqlFile) {
        List<String> sqlContentOld = sqlFile.getSqlContent();
        String procedureName = sqlFile.getSqlContent().get(0).split("PROCEDURE")[1].trim();
        String sqlReplaced = AlterIFExistsRoutine(procedureName);
        sqlContentOld.add(0, sqlReplaced);
        sqlContentOld.add(sqlContentOld.size(), "GO");
        return new SQLFile(sqlContentOld);
    }

    String AlterIFExistsRoutine(String procedureName) {
        return "IF EXISTS ( SELECT * \n" +
                "            FROM   sysobjects \n" +
                "            WHERE  id = object_id(N'" + procedureName + "') \n" +
                "                   and OBJECTPROPERTY(id, N'IsProcedure') = 1 )\n" +
                "BEGIN\n" +
                "    DROP PROCEDURE " + procedureName + "\n" +
                "END\n" +
                "GO";
    }

    @NotNull
    private SQLFile replaceCreateWithUpdate(SQLFile sqlFile, String entity) {
        List<String> sqlContentOld = sqlFile.getSqlContent();
        String sqlContentCreateProcedure = sqlFile.getSqlContent().get(0);
        String sqlReplaced = sqlContentCreateProcedure.replace("CREATE " + entity, "DROP " + entity);
        sqlContentOld.add(0, sqlReplaced);
        sqlContentOld.add(1, "GO");
        int lastLineIndex = sqlContentOld.size() - 1;
        String lastLine = sqlContentOld.get(lastLineIndex) + ";";
        sqlContentOld.set(lastLineIndex, lastLine);
        return new SQLFile(sqlContentOld);
    }

    @NotNull
    private VirtualDirectoryImpl getDatabaseFolder(AnActionEvent event) {
        Object project = event.getData(PlatformDataKeys.SELECTED_ITEM);
        if (project instanceof SolutionExplorerNodeRider) {
            SolutionExplorerNodeRider node = (SolutionExplorerNodeRider) project;
            VirtualFile virtualFile = node.getVirtualFile();
            if (virtualFile instanceof VirtualDirectoryImpl) {
                return (VirtualDirectoryImpl) virtualFile;
            }
        }
        return null;
    }

    @Override
    public void update(AnActionEvent event) {
        Presentation presentation = event.getPresentation();

        Object project = event.getData(PlatformDataKeys.SELECTED_ITEM);
        if (project instanceof SolutionExplorerNodeRider) {
            SolutionExplorerNodeRider node = (SolutionExplorerNodeRider) project;
            VirtualFile virtualFile = node.getVirtualFile();
            if (virtualFile instanceof VirtualDirectoryImpl)
            {
                show(presentation);
                return;
            }
        }
        hide(presentation);
    }

    private static void show(Presentation presentation) {
        presentation.setEnabled(true);
        presentation.setVisible(true);
    }

    private static void hide(Presentation presentation) {
        presentation.setEnabled(false);
        presentation.setVisible(false);
    }

    private File getPublishScriptLocation(AnActionEvent event) {
        Project project = event.getData(PlatformDataKeys.PROJECT);
        String projectFilePath = project.getBasePath();
        String dataBaseProject = projectFilePath + "/Database";

        return new File(dataBaseProject);
    }

    public ArrayList<SQLFile> getSQLFiles(final VirtualDirectoryImpl folder) {
        ArrayList<SQLFile> sqlFiles = new ArrayList<>();
        for (final VirtualFile fileEntry : folder.getChildren()) {
            if (fileEntry instanceof VirtualDirectoryImpl) {
                sqlFiles.addAll(getSQLFiles((VirtualDirectoryImpl) fileEntry));
            } else {
                String extension = getFileExtension(fileEntry);
                if (extension.equals(SQLFile.EXTENSION)) {
                    List<String> sqlContent = readContent(fileEntry);
                    SQLFile sqlFile = new SQLFile(sqlContent);
                    sqlFiles.add(sqlFile);
                }
            }
        }
        return sqlFiles;
    }

    private List<String> readContent(VirtualFile fileEntry) {
        try {
            InputStream fileInputStream = fileEntry.getInputStream();
            UnicodeBOMInputStream unicodeBOMInputStream = new UnicodeBOMInputStream(fileInputStream);
            unicodeBOMInputStream.skipBOM();
            InputStreamReader fisWithpoutBoms = new InputStreamReader(unicodeBOMInputStream);
            BufferedReader br = new BufferedReader(fisWithpoutBoms);

            String line;
            List<String> lines = new ArrayList<>();
            while ((line = br.readLine()) != null) {
                lines.add(line);
            }

            return lines;
        } catch (IOException e) {
            Messages.showErrorDialog("Could not read sql file: " + fileEntry.getName(), publishFailedTitle);
            return null;
        }
    }

    private String getFileExtension(VirtualFile fileEntry) {
        String[] splits = fileEntry.getName().split("\\.");

        String extension = "";

        if (splits.length >= 2) {
            extension = splits[splits.length - 1];
        }
        return extension;
    }
}