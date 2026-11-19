package com.quirogaj.vistio.data

import org.json.JSONObject

internal data class CameraSize(val width: Int, val height: Int) {
    val value: String get() = "$width" + "x$height"

    val displayName: String
        get() {
            val divisor = gcd(width, height)
            return "$width×$height · " + (width / divisor) + ":" + (height / divisor)
        }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}

internal data class CameraLens(
    val id: String,
    val facing: String,
    val label: String,
    val sizes: List<CameraSize>,
    val rotation: Int?,
    val mirror: Boolean?,
) {
    val displayName: String
        get() {
            val side = when (facing) {
                "back" -> "Trasera"
                "front" -> "Frontal"
                "external" -> "Externa"
                else -> "Cámara"
            }
            return "$side · $label"
        }
}

internal data class CameraSettings(
    val lenses: List<CameraLens>,
    val selected: CameraLens?,
    val resolution: String?,
    val rotation: Int?,
    val mirror: Boolean?,
) {
    companion object {
        fun parse(json: JSONObject): CameraSettings {
            val settings = json.optJSONObject("settings")
            val cameras = json.optJSONArray("cameras")
            val lenses = buildList {
                for (index in 0 until (cameras?.length() ?: 0)) {
                    val lens = cameras?.optJSONObject(index) ?: continue
                    val id = lens.text("id") ?: continue
                    val controls = lens.optJSONObject("lensSettings")
                    add(CameraLens(
                        id, lens.text("facing").orEmpty(),
                        lens.text("label") ?: "Lente $id",
                        lens.sizes(),
                        controls?.rotation(), controls?.mirror(),
                    ))
                }
            }.distinctBy(CameraLens::id)
            val id = settings?.text("cameraId")
            val selected = lenses.firstOrNull { it.id == id }
                ?: lenses.singleOrNull { it.id.substringAfter(':') == id }
                ?: lenses.singleOrNull { it.facing == id }
                ?: lenses.singleOrNull()
            return CameraSettings(
                lenses, selected, settings?.text("streamRes")?.lowercase(),
                selected?.rotation ?: settings?.rotation(), selected?.mirror ?: settings?.mirror(),
            )
        }

        private fun JSONObject.text(key: String): String? =
            if (isNull(key)) null else optString(key).trim().takeIf(String::isNotEmpty)

        private fun JSONObject.sizes(): List<CameraSize> {
            val sizes = optJSONArray("sizes") ?: return emptyList()
            return buildList {
                for (index in 0 until sizes.length()) {
                    val size = sizes.optJSONObject(index) ?: continue
                    val width = size.optInt("w")
                    val height = size.optInt("h")
                    if (width > 0 && height > 0) add(CameraSize(width, height))
                }
            }.distinct()
        }

        private fun JSONObject.rotation(): Int? = text("rotate")?.toIntOrNull()
            ?.let { ((it % 360) + 360) % 360 }

        private fun JSONObject.mirror(): Boolean? = text("mirror")?.lowercase()?.toBooleanStrictOrNull()
    }
}

internal sealed interface CameraControl {
    val parameter: String
    val value: String
    fun matches(settings: CameraSettings): Boolean

    data class Lens(val id: String) : CameraControl {
        override val parameter = "camera"
        override val value = id
        override fun matches(settings: CameraSettings) = settings.selected?.id == id
    }

    data class Resolution(override val value: String) : CameraControl {
        override val parameter = "resolution"
        override fun matches(settings: CameraSettings) = settings.resolution == value
    }

    data class Rotation(val degrees: Int) : CameraControl {
        override val parameter = "rotate"
        override val value = degrees.toString()
        override fun matches(settings: CameraSettings) = settings.rotation == degrees
    }

    data class Mirror(val enabled: Boolean) : CameraControl {
        override val parameter = "mirror"
        override val value = enabled.toString()
        override fun matches(settings: CameraSettings) = settings.mirror == enabled
    }
}
