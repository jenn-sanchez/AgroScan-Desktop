import unittest
import cv2

from src.video import calcular_salto_frames


class TestProcesamientoVideo(unittest.TestCase):

    def test_calculo_salto_frames(self):
        # 30 FPS y un intervalo de 2 segundos
        resultado = calcular_salto_frames(30, 2)

        self.assertEqual(resultado, 60)

    def test_salto_minimo_de_frames(self):
        # El salto calculado no debe ser menor que 1
        resultado = calcular_salto_frames(30, 0.01)

        self.assertEqual(resultado, 1)

    def test_lectura_video(self):
        ruta_video = "data/videos/video.mp4"
        video = cv2.VideoCapture(ruta_video)

        try:
            self.assertTrue(
                video.isOpened(),
                "No se pudo abrir el video de entrada"
            )

            fps = video.get(cv2.CAP_PROP_FPS)
            ancho = video.get(cv2.CAP_PROP_FRAME_WIDTH)
            alto = video.get(cv2.CAP_PROP_FRAME_HEIGHT)
            total_frames = video.get(cv2.CAP_PROP_FRAME_COUNT)

            self.assertGreater(fps, 0)
            self.assertGreater(ancho, 0)
            self.assertGreater(alto, 0)
            self.assertGreater(total_frames, 0)

        finally:
            video.release()


if __name__ == "__main__":
    unittest.main(verbosity=2)