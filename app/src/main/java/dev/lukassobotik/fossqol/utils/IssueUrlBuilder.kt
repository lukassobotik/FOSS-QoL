package dev.lukassobotik.fossqol.utils

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class GitHubIssueUrlBuilder(
    private val template: String = "bug_report.yml" // your YAML file name
) {

    /**
     * Builds a GitHub issue URL with pre-filled parameters based on your YAML issue form.
     *
     * @param bugDescription Description of the bug (for the field with id "bug_description").
     * @param activity       Activity in which the bug occurred (for the field with id "activity"). Optional.
     * @param version        The app version (for the field with id "version").
     * @param stepsToReproduce Steps to reproduce the bug (for the field with id "steps_to_reproduce").
     * @param logs           Relevant log output (for the field with id "logs"). Optional.
     * @return The complete URL as a String.
     */
    fun buildUrl(
        bugDescription: String,
        activity: String? = null,
        version: String,
        stepsToReproduce: String,
        logs: String? = null
    ): String {
        val baseUrl = "https://github.com/lukassobotik/foss-qol/issues/new"
        // Create a list of key-value pairs for the query parameters.
        val queryParams = mutableListOf<Pair<String, String>>().apply {
            add("template" to template)
            add("bug_description" to bugDescription)
            activity?.takeIf { it.isNotBlank() }?.let { add("activity" to it) }
            add("version" to version)
            add("steps_to_reproduce" to stepsToReproduce)
            logs?.takeIf { it.isNotBlank() }?.let { add("logs" to it) }
        }

        // Build the query string with proper URL encoding.
        val queryString = queryParams.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8.toString())}=" +
                    "${URLEncoder.encode(value, StandardCharsets.UTF_8.toString())}"
        }
        return "$baseUrl?$queryString"
    }

    companion object {
        /**
         * A convenience method to build the URL without needing to create an instance explicitly.
         */
        fun build(
            bugDescription: String,
            activity: String? = null,
            version: String,
            stepsToReproduce: String,
            logs: String? = null,
            template: String = "bug_report.yml"
        ): String {
            return GitHubIssueUrlBuilder(template).buildUrl(
                bugDescription = bugDescription,
                activity = activity,
                version = version,
                stepsToReproduce = stepsToReproduce,
                logs = logs
            )
        }
    }
}
