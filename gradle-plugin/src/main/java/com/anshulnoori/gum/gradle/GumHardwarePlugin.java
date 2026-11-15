package com.anshulnoori.gum.gradle;

import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import java.util.Arrays;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Bundling;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.attributes.java.TargetJvmEnvironment;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.TaskProvider;

/**
 * Generates the FTC robot configuration from the {@code RobotConfig} object in
 * {@code src/hardware/kotlin}, inside the FTC app module that applies this plugin.
 *
 * <p>The hardware sources are compiled twice: once for the build machine, so the generator can run
 * the config before resources are packaged, and once with the rest of TeamCode, so code can use
 * the same declarations on the robot. The build-machine compilation sees only Gum's hardware
 * module, so those sources cannot use the FTC SDK.
 */
public class GumHardwarePlugin implements Plugin<Project> {
    static final String GUM_VERSION = "0.1.0-SNAPSHOT";
    /** Compiles the hardware sources for the build machine. Keep in step with the app's Kotlin plugin. */
    static final String KOTLIN_VERSION = "2.3.20";
    static final String SOURCES = "src/hardware/kotlin";

    @Override
    public void apply(Project project) {
        project.getPluginManager().withPlugin("com.android.application", plugin -> configure(project));
    }

    private void configure(Project project) {
        project.getExtensions().getByType(ApplicationExtension.class)
                .getSourceSets().getByName("main").getKotlin().srcDir(SOURCES);

        Configuration hardware = jvmClasspath(project, "gumHardware",
                "com.anshulnoori.gum:hardware:" + GUM_VERSION);
        Configuration compiler = jvmClasspath(project, "gumKotlinCompiler",
                "org.jetbrains.kotlin:kotlin-compiler-embeddable:" + KOTLIN_VERSION);

        Provider<Directory> classes = project.getLayout().getBuildDirectory().dir("gum/hardware-classes");
        TaskProvider<JavaExec> compile = project.getTasks().register("compileGumHardware", JavaExec.class, task -> {
            task.setDescription("Compiles " + SOURCES + " for the build machine.");
            task.setClasspath(compiler);
            task.getMainClass().set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
            task.getInputs().dir(SOURCES).withPropertyName("sources");
            task.getInputs().files(hardware).withPropertyName("hardwareClasspath");
            task.getOutputs().dir(classes).withPropertyName("classes");
            task.doFirst(t -> project.delete(classes));
            task.getArgumentProviders().add(() -> Arrays.asList(
                    "-d", classes.get().getAsFile().getPath(),
                    "-classpath", hardware.getAsPath(),
                    "-no-stdlib", "-no-reflect", "-jvm-target", "1.8",
                    project.file(SOURCES).getPath()));
        });

        project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class).onVariants(
                project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class).selector().all(),
                variant -> {
                    String name = Character.toUpperCase(variant.getName().charAt(0)) + variant.getName().substring(1);
                    TaskProvider<GenerateRobotConfig> generate = project.getTasks().register(
                            "generate" + name + "GumRobotConfig", GenerateRobotConfig.class, task -> {
                                task.setDescription("Writes the robot configuration and its activation hook.");
                                task.setClasspath(hardware);
                                task.getMainClass().set("com.anshulnoori.gum.hardware.Generate");
                                task.getClasses().fileProvider(compile.map(t -> classes.get().getAsFile()));
                                task.getNamespace().set(variant.getNamespace());
                            });
                    variant.getSources().getRes().addGeneratedSourceDirectory(generate, GenerateRobotConfig::getResDirectory);
                    variant.getSources().getJava().addGeneratedSourceDirectory(generate, GenerateRobotConfig::getJavaDirectory);
                });
    }

    private static Configuration jvmClasspath(Project project, String name, String dependency) {
        Configuration configuration = project.getConfigurations().create(name, c -> {
            c.setCanBeConsumed(false);
            c.setCanBeResolved(true);
            c.setVisible(false);
            c.attributes(a -> {
                a.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, Usage.JAVA_RUNTIME));
                a.attribute(Category.CATEGORY_ATTRIBUTE, project.getObjects().named(Category.class, Category.LIBRARY));
                a.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        project.getObjects().named(LibraryElements.class, LibraryElements.JAR));
                a.attribute(Bundling.BUNDLING_ATTRIBUTE, project.getObjects().named(Bundling.class, Bundling.EXTERNAL));
                a.attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
                        project.getObjects().named(TargetJvmEnvironment.class, TargetJvmEnvironment.STANDARD_JVM));
            });
        });
        project.getDependencies().add(name, dependency);
        return configuration;
    }
}
