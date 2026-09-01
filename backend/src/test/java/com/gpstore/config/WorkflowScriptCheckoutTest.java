package com.gpstore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A workflow job that runs a script FROM THIS REPOSITORY must check the
 * repository out first.
 *
 * WHY THIS TEST EXISTS. The production deploy jobs are pure SSH - the entire
 * deploy is a script that runs on the VPS - so they never needed the
 * repository on the runner and did not check it out. A later change added one
 * step that reads a file out of the repository to log the runner's egress
 * address, which is a diagnostic for the deploys that occasionally cannot
 * reach the VPS. Its first line is `chmod +x .github/scripts/...`, so on a
 * runner with no checkout the job died there:
 *
 *   chmod: cannot access '.github/scripts/log_runner_egress.sh'
 *
 * Six consecutive releases failed on that line before anyone looked at a
 * deploy log, because the app's own CI was green the whole time - the failure
 * was in the step that ships it, not the ones that test it. The diagnostic
 * added to explain unreliable deploys became the reason there were none.
 *
 * The mistake is invisible in review: the step is correct, and so is the job;
 * they are only wrong together, and the two facts sit ninety lines apart in
 * the file. That is exactly the kind of thing worth asserting mechanically
 * rather than remembering.
 */
@DisplayName("A job that runs a repo script checks the repo out")
class WorkflowScriptCheckoutTest {

    /** Paths that only exist if the repository has been checked out. */
    private static final List<String> REPO_PATHS =
            List.of(".github/scripts/", "deploy/production/", "tool/", "load-tests/");

    private static Path workflowsDir() {
        Path fromModule = Path.of("../.github/workflows");
        return Files.isDirectory(fromModule) ? fromModule : Path.of(".github/workflows");
    }

    @Test
    @DisplayName("every job referencing a repository path also runs actions/checkout")
    void jobsRunningRepoScriptsCheckOutFirst() throws IOException {
        Path dir = workflowsDir();
        assertTrue(Files.isDirectory(dir), "cannot find .github/workflows from " + dir.toAbsolutePath());

        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                // Deliberately a line-oriented scan rather than a YAML parse.
                // A parser would need the whole GitHub Actions schema to find
                // job boundaries, and the property being checked is textual:
                // does this job's block mention a repo path, and does the same
                // block use actions/checkout.
                for (JobBlock job : jobsIn(Files.readString(file))) {
                    boolean usesRepoPath = REPO_PATHS.stream().anyMatch(job.body::contains);
                    if (usesRepoPath && !job.body.contains("actions/checkout")) {
                        offenders.add(file.getFileName() + " -> job '" + job.name + "'");
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "These jobs run something out of the repository but never check it out, "
                        + "so the step fails with 'No such file or directory' on a bare runner:\n  "
                        + String.join("\n  ", offenders));
    }

    private record JobBlock(String name, String body) {}

    /**
     * Splits a workflow into job blocks by indentation. Jobs are the keys at
     * exactly two spaces under {@code jobs:}, and a job's body runs until the
     * next such key.
     */
    private static List<JobBlock> jobsIn(String yaml) {
        List<JobBlock> jobs = new ArrayList<>();
        String[] lines = yaml.split("\n", -1);

        int i = 0;
        while (i < lines.length && !lines[i].startsWith("jobs:")) {
            i++;
        }

        String currentName = null;
        StringBuilder body = new StringBuilder();
        for (i = i + 1; i < lines.length; i++) {
            String line = lines[i];
            boolean isJobKey = line.matches("^  [A-Za-z0-9_-]+:\\s*$");
            if (isJobKey) {
                if (currentName != null) {
                    jobs.add(new JobBlock(currentName, body.toString()));
                }
                currentName = line.trim().replace(":", "");
                body = new StringBuilder();
            } else if (currentName != null) {
                body.append(line).append('\n');
            }
        }
        if (currentName != null) {
            jobs.add(new JobBlock(currentName, body.toString()));
        }
        return jobs;
    }

    @Test
    @DisplayName("the scan actually finds jobs, so a pass cannot mean it found none")
    void theScanIsNotVacuous() throws IOException {
        // Without this, a change that broke jobsIn() would turn every future
        // run of the test above into a silent pass over an empty list - the
        // same class of false green this test was written to prevent.
        Path deploy = workflowsDir().resolve("deploy-production.yml");
        List<JobBlock> jobs = jobsIn(Files.readString(deploy));

        assertTrue(jobs.size() >= 4,
                "expected several jobs in deploy-production.yml, found " + jobs.size());
        assertTrue(jobs.stream().anyMatch(j -> j.name.equals("deploy")),
                "the 'deploy' job should be among " + jobs.stream().map(JobBlock::name).toList());
        assertTrue(
                jobs.stream()
                        .filter(j -> j.name.equals("deploy"))
                        .anyMatch(j -> j.body.contains("log_runner_egress")),
                "the deploy job should still be the one that logs the egress address");
    }
}
