package com.anshulnoori.gum.gradle;

import java.util.Arrays;
import javax.inject.Inject;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Compiles the hardware sources for the build machine with the Kotlin compiler on this task's
 * classpath. Its inputs are declared, so it is up to date when nothing changed.
 */
@CacheableTask
public abstract class CompileGumHardware extends JavaExec {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSources();

    /** Gum's hardware module, the only library the sources can use. */
    @Classpath
    public abstract ConfigurableFileCollection getHardwareClasspath();

    @OutputDirectory
    public abstract DirectoryProperty getClassesDirectory();

    @Inject
    protected abstract FileSystemOperations getFileSystem();

    @Override
    @TaskAction
    public void exec() {
        getFileSystem().delete(spec -> spec.delete(getClassesDirectory()));
        setArgs(Arrays.asList(
                "-d", getClassesDirectory().get().getAsFile().getPath(),
                "-classpath", getHardwareClasspath().getAsPath(),
                "-no-stdlib", "-no-reflect", "-jvm-target", "1.8",
                getSources().get().getAsFile().getPath()));
        super.exec();
    }
}
