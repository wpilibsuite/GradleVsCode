package edu.wpi.first.vscode;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Set;

import javax.inject.Inject;

import com.google.gson.GsonBuilder;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import edu.wpi.first.vscode.tooling.ToolChainGenerator;
import edu.wpi.first.vscode.tooling.models.ToolChains;

public class VsCodeConfigurationTask extends DefaultTask {
  private RegularFileProperty configFile;

  @Inject
  public VsCodeConfigurationTask(ObjectFactory factory) {
    configFile = factory.fileProperty();
  }

  @OutputFile
  public RegularFileProperty getConfigFile() {
    return configFile;
  }

  @Input
  public Set<ToolChains> getToolChains() {
    return ToolChainGenerator.generateToolChains(getProject());
  }

  @TaskAction
  public void generate() {
    Set<ToolChains> toolChains = getToolChains();
    VsCodeConfigurationExtension ext = getProject().getExtensions().getByType(VsCodeConfigurationExtension.class);

    GsonBuilder builder = new GsonBuilder().disableHtmlEscaping();

    if (ext.getPrettyPrinting()) {
      builder.setPrettyPrinting();
    }

    String json = builder.create().toJson(toolChains);

    File file = configFile.getAsFile().get();
    file.getParentFile().mkdirs();

    try (BufferedWriter writer = Files.newBufferedWriter(file.toPath())) {
      writer.append(json);
    } catch (IOException ex) {

    }
  }
}
