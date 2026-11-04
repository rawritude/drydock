package io.github.rawritude.drydock.engine

/**
 * Decides whether a partially downloaded control file can be resumed against
 * freshly probed server metadata. Anything ambiguous falls back to [Fresh],
 * which deletes partial data and restarts — never resume into unknown bytes.
 */
object ResumePolicy {

    sealed interface Decision {
        data class Resume(val control: ControlFile) : Decision
        data object Fresh : Decision
    }

    fun decide(previous: ControlFile?, fresh: RemoteFileInfo): Decision {
        if (previous == null) return Decision.Fresh
        if (previous.version != ControlFile.CURRENT_VERSION) return Decision.Fresh
        if (!fresh.acceptRanges) return Decision.Fresh
        val total = fresh.contentLength ?: return Decision.Fresh
        val oldTotal = previous.totalBytes ?: return Decision.Fresh
        if (total != oldTotal || total <= 0) return Decision.Fresh

        val oldEtag = previous.etag
        val newEtag = fresh.etag
        if (oldEtag != null && newEtag != null && oldEtag != newEtag) return Decision.Fresh

        if (oldEtag == null && newEtag == null) {
            val oldModified = previous.lastModified
            val newModified = fresh.lastModified
            if (oldModified != null && newModified != null && oldModified != newModified) {
                return Decision.Fresh
            }
        }

        if (!tilesExactly(previous.segments, total)) return Decision.Fresh

        return Decision.Resume(
            previous.copy(
                finalUrl = fresh.finalUrl.toString(),
                etag = newEtag ?: previous.etag,
                lastModified = fresh.lastModified ?: previous.lastModified,
            ),
        )
    }

    private fun tilesExactly(segments: List<SegmentState>, total: Long): Boolean {
        if (segments.isEmpty()) return false
        var expected = 0L
        for (segment in segments) {
            if (segment.start != expected) return false
            if (segment.endExclusive <= segment.start) return false
            if (segment.done < 0 || segment.done > segment.length) return false
            expected = segment.endExclusive
        }
        return expected == total
    }
}
