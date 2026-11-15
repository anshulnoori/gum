package com.anshulnoori.gum.gradle;

import java.util.Arrays;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Runs Gum's generator on the compiled hardware sources. AGP chooses the output directories. */
@CacheableTask
public abstract class GenerateRobotConfig extends JavaExec {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getClasses();

    @Input
    public abstract Property<String> getNamespace();

    @OutputDirectory
    public abstract DirectoryProperty getResDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getJavaDirectory();

    @Override
    @TaskAction
    public void exec() {
        getProject().delete(getResDirectory(), getJavaDirectory());
        setArgs(Arrays.asList(
                getClasses().get().getAsFile().getPath(),
                getResDirectory().get().getAsFile().getPath(),
                getJavaDirectory().get().getAsFile().getPath(),
                getNamespace().get()));
        super.exec();
    }
}
