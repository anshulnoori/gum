package com.anshulnoori.gum.gradle;

import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.gradle.api.GradleException;
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
import org.gradle.api.tasks.TaskProvider;
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePlugin;

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
    static final String SOURCES = "src/hardware/kotlin";

    @Override
    public void apply(Project project) {
        project.getPluginManager().withPlugin("com.android.application", plugin -> configure(project));
    }

    private void configure(Project project) {
        project.getExtensions().getByType(ApplicationExtension.class)
                .getSourceSets().getByName("main").getKotlin().srcDir(SOURCES);

        // The hardware module of this plugin's own version, compiled by the app's Kotlin version.
        Configuration hardware = jvmClasspath(project, "gumHardware",
                project.provider(() -> "com.anshulnoori.gum:hardware:" + gumVersion()));
        Configuration compiler = jvmClasspath(project, "gumKotlinCompiler",
                project.provider(() -> "org.jetbrains.kotlin:kotlin-compiler-embeddable:" + kotlinVersion(project)));

        Provider<Directory> classes = project.getLayout().getBuildDirectory().dir("gum/hardware-classes");
        TaskProvider<CompileGumHardware> compile = project.getTasks().register("compileGumHardware", CompileGumHardware.class, task -> {
            task.setDescription("Compiles " + SOURCES + " for the build machine.");
            task.setClasspath(compiler);
            task.getMainClass().set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
            task.getSources().set(project.getLayout().getProjectDirectory().dir(SOURCES));
            task.getHardwareClasspath().from(hardware);
            task.getClassesDirectory().set(classes);
        });

        project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class).onVariants(
                project.getExtensions().getByType(ApplicationAndroidComponentsExtension.class).selector().all(),
                variant -> {
                    String name = Character.toUpperCase(variant.getName().charAt(0)) + variant.getName().substring(1);
                    TaskProvider<GenerateRobotConfig> generate = project.getTasks().register(
                            "generate" + name + "GumRobotConfig", GenerateRobotConfig.class, task -> {
                                task.setDescription("Writes the robot configuration, its activation hook, and program builders.");
                                task.setClasspath(hardware);
                                task.getMainClass().set("com.anshulnoori.gum.hardware.Generate");
                                task.getClasses().set(compile.flatMap(CompileGumHardware::getClassesDirectory));
                                task.getNamespace().set(variant.getNamespace());
                            });
                    variant.getSources().getRes().addGeneratedSourceDirectory(generate, GenerateRobotConfig::getResDirectory);
                    variant.getSources().getJava().addGeneratedSourceDirectory(generate, GenerateRobotConfig::getJavaDirectory);
                    // Kotlin compiles generated Java source directories; it does not see generated Kotlin ones.
                    variant.getSources().getJava().addGeneratedSourceDirectory(generate, GenerateRobotConfig::getKotlinDirectory);
                });
    }

    /** This plugin's version, which the build writes into its resources. */
    private static String gumVersion() {
        try (InputStream in = GumHardwarePlugin.class.getResourceAsStream("gum-version.txt")) {
            if (in == null) throw new GradleException("Gum's Gradle plugin is missing its version resource");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            throw new GradleException("Gum's Gradle plugin cannot read its version", error);
        }
    }

    /** The version of the Kotlin plugin the app applies, so the hardware sources compile the same way. */
    private static String kotlinVersion(Project project) {
        return project.getPlugins().withType(KotlinBasePlugin.class).stream()
                .map(KotlinBasePlugin::getPluginVersion)
                .findFirst()
                .orElseThrow(() -> new GradleException("Apply the Kotlin Android plugin before Gum's hardware plugin"));
    }

    private static Configuration jvmClasspath(Project project, String name, Provider<String> dependency) {
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
        configuration.getDependencies().addLater(dependency.map(project.getDependencies()::create));
        return configuration;
    }
}
