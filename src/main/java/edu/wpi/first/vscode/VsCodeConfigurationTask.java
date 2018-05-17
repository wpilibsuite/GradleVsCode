package edu.wpi.first.vscode;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.gson.GsonBuilder;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.os.OperatingSystem;
import org.gradle.language.base.LanguageSourceSet;
import org.gradle.language.c.CSourceSet;
import org.gradle.language.cpp.CppSourceSet;
import org.gradle.language.nativeplatform.HeaderExportingSourceSet;
import org.gradle.nativeplatform.NativeBinarySpec;
import org.gradle.nativeplatform.NativeDependencySet;
import org.gradle.nativeplatform.platform.internal.NativePlatformInternal;
import org.gradle.nativeplatform.toolchain.Clang;
import org.gradle.nativeplatform.toolchain.GccPlatformToolChain;
import org.gradle.nativeplatform.toolchain.NativeToolChain;
import org.gradle.nativeplatform.toolchain.VisualCppPlatformToolChain;
import org.gradle.nativeplatform.toolchain.internal.SystemLibraries;
import org.gradle.nativeplatform.toolchain.internal.ToolType;
import org.gradle.nativeplatform.toolchain.internal.gcc.metadata.GccMetadata;
import org.gradle.nativeplatform.toolchain.internal.msvcpp.UcrtInstall;
import org.gradle.nativeplatform.toolchain.internal.msvcpp.VisualCpp;
import org.gradle.nativeplatform.toolchain.internal.msvcpp.VisualStudioInstall;
import org.gradle.nativeplatform.toolchain.internal.msvcpp.WindowsSdk;
import org.gradle.nativeplatform.toolchain.internal.msvcpp.WindowsSdkInstall;
import org.gradle.nativeplatform.toolchain.internal.tools.CommandLineToolConfigurationInternal;
import org.gradle.nativeplatform.toolchain.internal.tools.CommandLineToolSearchResult;
import org.gradle.nativeplatform.toolchain.internal.tools.ToolSearchPath;
import org.gradle.platform.base.internal.toolchain.SearchResult;

public class VsCodeConfigurationTask extends DefaultTask {

  static class Source {
    public Set<String> srcDirs = new HashSet<>();
    public Set<String> includes = new HashSet<>();
    public Set<String> excludes = new HashSet<>();
  }

  static class SourceSet {
    public Source source = new Source();
    public Source exportedHeaders = new Source();
    public boolean cpp = true;
  }

  static class BinaryObject {
    public String componentName = "";
    public List<SourceSet> sourceSets = new ArrayList<>();
    public Set<String> libHeaders = new HashSet<>();
    public List<String> cppArgs = new ArrayList<>();
    public List<String> cArgs = new ArrayList<>();
    public Map<String, String> cppMacros = new HashMap<>();
    public Map<String, String> cMacros = new HashMap<>();
  }

  static class ToolChains {
    public String architecture;
    public String operatingSystem;
    public String flavor;
    public String buildType;
    public String cppPath = "";
    public String cPath = "";
    public boolean msvc = true;

    public transient boolean hasBeenSetUp = false;

    public Set<String> systemCppIncludes = new HashSet<>();
    public Map<String, String> systemCppMacros = new HashMap<>();
    public Set<String> systemCppArgs = new HashSet<>();
    public Set<String> systemCIncludes = new HashSet<>();
    public Map<String, String> systemCMacros = new HashMap<>();
    public Set<String> systemCArgs = new HashSet<>();

    public List<BinaryObject> binaries = new ArrayList<>();

    @Override
    public boolean equals(Object o) {
      if (o == this)
        return true;
      if (!(o instanceof ToolChains)) {
        return false;
      }

      ToolChains tc = (ToolChains) o;

      return tc.architecture.equals(architecture) && tc.operatingSystem.equals(operatingSystem)
          && tc.flavor.equals(flavor) && tc.buildType.equals(buildType);
    }

    @Override
    public int hashCode() {
      return Objects.hash(architecture, operatingSystem, flavor, buildType);
    }
  }

  @OutputFile
  public RegularFileProperty configFile = newOutputFile();

  @TaskAction
  public void generate() {
    Set<ToolChains> toolChains = new HashSet<>();

    VsCodeConfigurationExtension ext = getProject().getExtensions().getByType(VsCodeConfigurationExtension.class);

    for (NativeBinarySpec bin : ext.binaries) {
      BinaryObject bo = new BinaryObject();

      for (LanguageSourceSet sSet : bin.getInputs()) {
        if (sSet instanceof HeaderExportingSourceSet) {
          HeaderExportingSourceSet hSet = (HeaderExportingSourceSet) sSet;
          SourceSet s = new SourceSet();

          for (File f : hSet.getSource().getSrcDirs()) {
            s.source.srcDirs.add(f.toString());
          }

          s.source.includes.addAll(hSet.getSource().getIncludes());
          s.source.excludes.addAll(hSet.getSource().getExcludes());

          for (File f : hSet.getExportedHeaders().getSrcDirs()) {
            s.exportedHeaders.srcDirs.add(f.toString());
          }

          s.exportedHeaders.includes.addAll(hSet.getExportedHeaders().getIncludes());
          s.exportedHeaders.excludes.addAll(hSet.getExportedHeaders().getExcludes());

          bo.sourceSets.add(s);

          if (sSet instanceof CppSourceSet) {
            s.cpp = true;
          } else if (sSet instanceof CSourceSet) {
            s.cpp = false;
          }

        }
      }

      for (NativeDependencySet dep : bin.getLibs()) {
        for (File f : dep.getIncludeRoots()) {
          bo.libHeaders.add(f.toString());
        }
      }

      bo.cArgs.addAll(bin.getcCompiler().getArgs());
      bo.cppArgs.addAll(bin.getCppCompiler().getArgs());
      bo.cppMacros.putAll(bin.getCppCompiler().getMacros());
      bo.cMacros.putAll(bin.getcCompiler().getMacros());

      ToolChains tc = new ToolChains();

      tc.flavor = bin.getFlavor().getName();
      tc.buildType = bin.getBuildType().getName();

      tc.architecture = bin.getTargetPlatform().getArchitecture().getName();
      tc.operatingSystem = bin.getTargetPlatform().getOperatingSystem().getName();

      bo.componentName = bin.getComponent().getName();

      CommandLineToolConfigurationInternal cppInternal = null;
      CommandLineToolConfigurationInternal cInternal = null;

      NativeToolChain toolChain = bin.getToolChain();

      if (!tc.hasBeenSetUp) {
        tc.hasBeenSetUp = true;

        for (VisualCppPlatformToolChain msvcPlat : ext.visualCppPlatforms) {
          if (msvcPlat.getPlatform().equals(bin.getTargetPlatform())) {
            tc.msvc = true;
            cppInternal = (CommandLineToolConfigurationInternal) msvcPlat.getCppCompiler();
            cInternal = (CommandLineToolConfigurationInternal) msvcPlat.getcCompiler();

            if (toolChain instanceof org.gradle.nativeplatform.toolchain.VisualCpp) {
              org.gradle.nativeplatform.toolchain.VisualCpp vcpp = (org.gradle.nativeplatform.toolchain.VisualCpp) toolChain;
              SearchResult<VisualStudioInstall> vsiSearch = ext.vsLocator.locateComponent(vcpp.getInstallDir());
              if (vsiSearch.isAvailable()) {
                VisualStudioInstall vsi = vsiSearch.getComponent();
                VisualCpp vscpp = vsi.getVisualCpp().forPlatform((NativePlatformInternal) bin.getTargetPlatform());
                tc.cppPath = vscpp.getCompilerExecutable().toString();
                tc.cPath = vscpp.getCompilerExecutable().toString();

                for (File f : vscpp.getIncludeDirs()) {
                  tc.systemCppIncludes.add(f.toString());
                }

                tc.systemCppMacros.putAll(vscpp.getPreprocessorMacros());

                for (File f : vscpp.getIncludeDirs()) {
                  tc.systemCIncludes.add(f.toString());
                }

                tc.systemCMacros.putAll(vscpp.getPreprocessorMacros());
              }

              SearchResult<UcrtInstall> ucrtSearch = ext.vsucrtLocator.locateComponent(vcpp.getWindowsSdkDir());
              if (ucrtSearch.isAvailable()) {
                UcrtInstall wsdki = ucrtSearch.getComponent();
                SystemLibraries wsdk = wsdki.getCRuntime((NativePlatformInternal) bin.getTargetPlatform());

                for (File f : wsdk.getIncludeDirs()) {
                  tc.systemCppIncludes.add(f.toString());
                }

                tc.systemCppMacros.putAll(wsdk.getPreprocessorMacros());

                for (File f : wsdk.getIncludeDirs()) {
                  tc.systemCIncludes.add(f.toString());
                }

                tc.systemCMacros.putAll(wsdk.getPreprocessorMacros());
              }

              SearchResult<WindowsSdkInstall> wsdkSearch = ext.vssdkLocator.locateComponent(vcpp.getWindowsSdkDir());
              if (wsdkSearch.isAvailable()) {
                WindowsSdkInstall wsdki = wsdkSearch.getComponent();
                WindowsSdk wsdk = wsdki.forPlatform((NativePlatformInternal) bin.getTargetPlatform());

                for (File f : wsdk.getIncludeDirs()) {
                  tc.systemCppIncludes.add(f.toString());
                }

                tc.systemCppMacros.putAll(wsdk.getPreprocessorMacros());

                for (File f : wsdk.getIncludeDirs()) {
                  tc.systemCIncludes.add(f.toString());
                }

                tc.systemCMacros.putAll(wsdk.getPreprocessorMacros());
              }
            }
          }

          for (GccPlatformToolChain gccPlat : ext.gccLikePlatforms) {
            if (gccPlat.getPlatform().equals(bin.getTargetPlatform())) {
              tc.msvc = false;
              cppInternal = (CommandLineToolConfigurationInternal) gccPlat.getCppCompiler();
              cInternal = (CommandLineToolConfigurationInternal) gccPlat.getcCompiler();
              tc.cppPath = gccPlat.getCppCompiler().getExecutable();
              tc.cPath = gccPlat.getcCompiler().getExecutable();

              ToolSearchPath tsp = new ToolSearchPath(OperatingSystem.current());
              CommandLineToolSearchResult cppSearch = tsp.locate(ToolType.CPP_COMPILER,
                  gccPlat.getCppCompiler().getExecutable());
              if (cppSearch.isAvailable()) {
                tc.cppPath = cppSearch.getTool().toString();
              }
              CommandLineToolSearchResult cSearch = tsp.locate(ToolType.C_COMPILER,
                  gccPlat.getcCompiler().getExecutable());
              if (cSearch.isAvailable()) {
                tc.cPath = cSearch.getTool().toString();
              }
            }
          }
        }

        List<String> list = new ArrayList<>();
        cppInternal.getArgAction().execute(list);

        for (int i = 0; i < list.size(); i++) {
          String trim = list.get(i).trim();
          if (trim.startsWith("-D") || trim.startsWith("/D")) {
            list.remove(i);
          } else {
            continue;
          }
          trim = trim.substring(2);
          if (trim.contains("=")) {
            String[] split = trim.split("=", 2);
            tc.systemCppMacros.put(split[0], split[1]);
          } else {
            tc.systemCppMacros.put(trim, "");
          }
        }

        tc.systemCppArgs.addAll(list);

        list.clear();

        cInternal.getArgAction().execute(list);

        for (int i = 0; i < list.size(); i++) {
          String trim = list.get(i).trim();
          if (trim.startsWith("-D") || trim.startsWith("/D")) {
            list.remove(i);
          } else {
            continue;
          }
          trim = trim.substring(2);
          if (trim.contains("=")) {
            String[] split = trim.split("=", 2);
            tc.systemCMacros.put(split[0], split[1]);
          } else {
            tc.systemCMacros.put(trim, "");
          }
        }

        tc.systemCArgs.addAll(list);


        for (GccPlatformToolChain gccPlat : ext.gccLikePlatforms) {
          if (gccPlat.getPlatform().equals(bin.getTargetPlatform())) {

            GccMetadataProvider gmp;
            List<String> additionalCppArgs = new ArrayList<>();
            List<String> additionalCArgs = new ArrayList<>();


            if (toolChain instanceof Clang) {
              gmp = GccMetadataProvider.forClang(ext.execActionFactory);
            } else {
              gmp = GccMetadataProvider.forGcc(ext.execActionFactory);
              additionalCppArgs.add("-xc++");
              for (String arg : tc.systemCppArgs) {
                if (arg.contains("-std=")) {
                  additionalCppArgs.add(arg);
                  break;
                }
              }
              additionalCArgs.add("-xc");
            }

            SearchResult<GccMetadata> md = gmp.getCompilerMetaData(new File(tc.cppPath), additionalCppArgs);
            if (md.isAvailable()) {
              SystemLibraries sl = md.getComponent().getSystemLibraries();
              for (File s : sl.getIncludeDirs()) {
                try {
                  tc.systemCppIncludes.add(s.getCanonicalPath());
                } catch (IOException e) {
                  e.printStackTrace();
                }
              }
              tc.systemCppMacros.putAll(sl.getPreprocessorMacros());
            }

            md = gmp.getCompilerMetaData(new File(tc.cPath), additionalCArgs);
            if (md.isAvailable()) {
              SystemLibraries sl = md.getComponent().getSystemLibraries();
              for (File s : sl.getIncludeDirs()) {
                try {
                  tc.systemCIncludes.add(s.getCanonicalPath());
                } catch (IOException e) {
                  e.printStackTrace();
                }
              }
              tc.systemCMacros.putAll(sl.getPreprocessorMacros());
            }
          }
        }
      }

      boolean added = toolChains.add(tc);
      if (!added) {
        for (ToolChains tc2 : toolChains) {
          if (tc.equals(tc2)) {
            tc = tc2;
          }
        }
      }

      tc.binaries.add(bo);

    }

    GsonBuilder builder = new GsonBuilder();

    builder.setPrettyPrinting();

    String json = builder.create().toJson(toolChains);

    File file = configFile.getAsFile().get();
    file.getParentFile().mkdirs();

    try (BufferedWriter writer = Files.newBufferedWriter(file.toPath())) {
      writer.append(json);
    } catch (IOException ex) {

    }
  }
}