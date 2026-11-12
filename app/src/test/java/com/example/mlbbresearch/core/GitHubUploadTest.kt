package com.example.mlbbresearch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// owner, 2026-11-12: non-video files together in one commit, each video in a commit of its own
class GitHubUploadTest {

    @Test fun nonVideosTogetherThenEachVideoAlone() {
        val names = listOf("s1.jsonl", "s1.mp4", "s1-settings.json", "s2.mp4", "s1-snap-1.jpg")
        val plan = GitHubUpload.plan(names, emptySet(), null)
        assertEquals(listOf(listOf("s1-settings.json", "s1-snap-1.jpg", "s1.jsonl"), listOf("s1.mp4"), listOf("s2.mp4")), plan)
    }

    @Test fun uploadedAndRunningRecordingsAreLeftOut() {
        val names = listOf("a.jsonl", "a.mp4", "b.jsonl", "b-snap-1.jpg")
        val plan = GitHubUpload.plan(names, setOf("a.jsonl"), recordingNow = "b.jsonl")
        assertEquals(listOf(listOf("a.mp4")), plan)
        assertTrue(GitHubUpload.plan(names, names.toSet(), null).isEmpty())
    }

    @Test fun messagesAndErrors() {
        assertEquals("Recording video: a.mp4", GitHubUpload.message(listOf("a.mp4")))
        assertEquals("Debug data: 2 files (a.jsonl ...)", GitHubUpload.message(listOf("a.jsonl", "b.json")))
        assertTrue(GitHubUpload.explain(401, "{}").contains("token"))
        assertTrue(GitHubUpload.explain(404, "{}").contains(GitHubUpload.BRANCH))
        assertEquals("recordings-for-debugging", GitHubUpload.BRANCH)
    }

    @Test fun pictureZipsGoAlone() {
        val plan = GitHubUpload.plan(listOf("a.jsonl", "pictures-x-p1.zip", "pictures-x-p2.zip"), emptySet(), null)
        assertEquals(listOf(listOf("a.jsonl"), listOf("pictures-x-p1.zip"), listOf("pictures-x-p2.zip")), plan)
        assertEquals("Training pictures: pictures-x-p1.zip", GitHubUpload.message(listOf("pictures-x-p1.zip")))
    }
}
