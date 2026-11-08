package org.agroscan.project.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max

/**
 * Lee un JPG o PNG. Con [maxLado] lo lee ya reducido (hasta ese largo en píxeles): así una miniatura no obliga a cargar en memoria un
 * mosaico de 4096 px entero. null si el archivo no existe o no es una imagen válida. Bloqueante: llamar fuera del hilo de UI.
 */
internal fun cargarImagen(archivo: File, maxLado: Int? = null): ImageBitmap? = runCatching {
    if (!archivo.isFile) return null
    ImageIO.createImageInputStream(archivo)?.use { entrada ->
        val lectores = ImageIO.getImageReaders(entrada)
        if (!lectores.hasNext()) return null
        val lector = lectores.next()
        try {
            lector.input = entrada
            val parametros = lector.defaultReadParam
            if (maxLado != null && maxLado > 0) {
                val paso = (max(lector.getWidth(0), lector.getHeight(0)) / maxLado).coerceAtLeast(1)
                parametros.setSourceSubsampling(paso, paso, 0, 0)
            }
            lector.read(0, parametros).toComposeImageBitmap()
        } finally {
            lector.dispose()
        }
    }
}.getOrNull()
