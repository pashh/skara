/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.bots.pr;

import org.openjdk.skara.bot.WorkItem;
import org.openjdk.skara.forge.*;
import org.openjdk.skara.host.HostUser;
import org.openjdk.skara.issuetracker.Comment;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class CheckWorkItem extends PullRequestWorkItem {
    private final Pattern metadataComments = Pattern.compile("<!-- (?:(add|remove) (?:contributor|reviewer))|(?:summary: ')|(?:solves: ')|(?:additional required reviewers)");
    private final Logger log = Logger.getLogger("org.openjdk.skara.bots.pr");
    static final Pattern ISSUE_ID_PATTERN = Pattern.compile("^(?:[A-Za-z][A-Za-z0-9]+-)?([0-9]+)$");

    CheckWorkItem(PullRequestBot bot, PullRequest pr, Consumer<RuntimeException> errorHandler) {
        super(bot, pr, errorHandler);
    }

    private String encodeReviewer(HostUser reviewer, CensusInstance censusInstance) {
        var census = censusInstance.census();
        var project = censusInstance.project();
        var namespace = censusInstance.namespace();
        var contributor = namespace.get(reviewer.id());
        if (contributor == null) {
            return "unknown-" + reviewer.id();
        } else {
            var censusVersion = census.version().format();
            var userName = contributor.username();
            return contributor.username() + project.isLead(userName, censusVersion) +
                    project.isReviewer(userName, censusVersion) + project.isCommitter(userName, censusVersion) +
                    project.isAuthor(userName, censusVersion);
        }
    }

    String getMetadata(CensusInstance censusInstance, String title, String body, List<Comment> comments,
                       List<Review> reviews, Set<String> labels, boolean isDraft) {
        try {
            var approverString = reviews.stream()
                                        .filter(review -> review.verdict() == Review.Verdict.APPROVED)
                                        .map(review -> encodeReviewer(review.reviewer(), censusInstance) + review.hash().hex())
                                        .sorted()
                                        .collect(Collectors.joining());
            var commentString = comments.stream()
                                        .filter(comment -> comment.author().id().equals(pr.repository().forge().currentUser().id()))
                                        .flatMap(comment -> comment.body().lines())
                                        .filter(line -> metadataComments.matcher(line).find())
                                        .collect(Collectors.joining());
            var labelString = labels.stream()
                                    .sorted()
                                    .collect(Collectors.joining());
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(title.getBytes(StandardCharsets.UTF_8));
            digest.update(body.getBytes(StandardCharsets.UTF_8));
            digest.update(approverString.getBytes(StandardCharsets.UTF_8));
            digest.update(commentString.getBytes(StandardCharsets.UTF_8));
            digest.update(labelString.getBytes(StandardCharsets.UTF_8));
            digest.update(isDraft ? (byte)0 : (byte)1);

            return Base64.getUrlEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Cannot find SHA-256");
        }
    }

    private boolean currentCheckValid(CensusInstance censusInstance, List<Comment> comments, List<Review> reviews, Set<String> labels) {
        var hash = pr.headHash();
        var metadata = getMetadata(censusInstance, pr.title(), pr.body(), comments, reviews, labels, pr.isDraft());
        var currentChecks = pr.checks(hash);

        if (currentChecks.containsKey("jcheck")) {
            var check = currentChecks.get("jcheck");
            // Check if the currently running check seems stale - perhaps the checker failed to complete
            if (check.completedAt().isEmpty()) {
                var runningTime = Duration.between(check.startedAt().toInstant(), Instant.now());
                if (runningTime.toMinutes() > 10) {
                    log.warning("Previous jcheck running for more than 10 minutes - checking again");
                } else {
                    log.finer("Jcheck in progress for " + runningTime.toMinutes() + " minutes, not starting another one");
                    return true;
                }
            } else {
                if (check.metadata().isPresent() && check.metadata().get().equals(metadata)) {
                    log.finer("No activity since last check, not checking again");
                    return true;
                } else {
                    log.info("PR updated after last check, checking again");
                    if (check.metadata().isPresent() && (!check.metadata().get().equals(metadata))) {
                        log.fine("Previous metadata: " + check.metadata().get() + " - current: " + metadata);
                    }
                }
            }
        }

        return false;
    }

    private boolean updateTitle() {
        var title = pr.title();
        var m = ISSUE_ID_PATTERN.matcher(title);
        var project = bot.issueProject();

        var newTitle = title;
        if (m.matches() && project != null) {
            var id = m.group(1);
            var issue = project.issue(id);
            if (issue.isPresent()) {
                newTitle = id + ": " + issue.get().title();
            }
        }

        if (!title.equals(newTitle)) {
            pr.setTitle(newTitle);
            return true;
        }

        return false;
    }

    @Override
    public String toString() {
        return "CheckWorkItem@" + pr.repository().name() + "#" + pr.id();
    }

    @Override
    public Collection<WorkItem> run(Path scratchPath) {
        // First determine if the current state of the PR has already been checked
        var census = CensusInstance.create(bot.censusRepo(), bot.censusRef(), scratchPath.resolve("census"), pr,
                                           bot.confOverrideRepository().orElse(null), bot.confOverrideName(), bot.confOverrideRef());
        var comments = pr.comments();
        var allReviews = pr.reviews();
        var labels = new HashSet<>(pr.labels());

        // Filter out the active reviews
        var activeReviews = CheckablePullRequest.filterActiveReviews(allReviews);
        if (!currentCheckValid(census, comments, activeReviews, labels)) {
            if (labels.contains("integrated")) {
                log.info("Skipping check of integrated PR");
                return List.of();
            }

            // If the title needs updating, we run the check again
            if (updateTitle()) {
                return List.of(new CheckWorkItem(bot, pr.repository().pullRequest(pr.id()), errorHandler));
            }

            try {
                var seedPath = bot.seedStorage().orElse(scratchPath.resolve("seeds"));
                var hostedRepositoryPool = new HostedRepositoryPool(seedPath);
                var localRepoPath = scratchPath.resolve("pr").resolve("check").resolve(pr.repository().name());
                var localRepo = PullRequestUtils.materialize(hostedRepositoryPool, pr, localRepoPath);

                CheckRun.execute(this, pr, localRepo, comments, allReviews, activeReviews, labels, census, bot.ignoreStaleReviews());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        // Must re-fetch PR after executing CheckRun
        var updatedPR = pr.repository().pullRequest(pr.id());
        return List.of(new CommandWorkItem(bot, updatedPR, errorHandler));
    }
}
