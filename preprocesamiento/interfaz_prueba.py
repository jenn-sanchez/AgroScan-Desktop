import json
import os
import queue
import subprocess
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, ttk
from src import config

RAIZ = Path(__file__).resolve().parent

VERDE = "#0b6b4d"
VERDE_OSCURO = "#085a40"
VERDE_CLARO = "#bff0d8"
AMBAR_CLARO = "#fef3c7"
AMBAR = "#92400e"
FONDO = "#eef6f2"
GRIS = "#6b7280"
TEXTO = "#111827"

def carpeta_salida_para(ruta_video):
    if ruta_video.parent.name.lower() == "video":
        return ruta_video.parent.parent
    return config.CARPETA_DATOS / "salidas" / ruta_video.stem

def etiqueta(padre, texto, tamano=10, negrita=False, color=TEXTO):
    estilo = "bold" if negrita else "normal"
    return tk.Label(
        padre, text=texto, bg="white", fg=color,
        font=("Segoe UI", tamano, estilo), anchor="w", justify="left",
    )

def boton(padre, texto, comando, principal=True):
    if principal:
        return tk.Button(
            padre, text=texto, command=comando, bg=VERDE, fg="white",
            activebackground=VERDE_OSCURO, activeforeground="white",
            relief="flat", font=("Segoe UI", 10, "bold"), padx=16, pady=8, cursor="hand2",
        )
    return tk.Button(
        padre, text=texto, command=comando, bg="white", fg=TEXTO,
        relief="flat", highlightthickness=1, highlightbackground="#d1d5db",
        font=("Segoe UI", 10), padx=14, pady=6, cursor="hand2",
    )

class TarjetaProcesamiento:
    def __init__(self, ventana):
        self.ventana = ventana
        self.cola = queue.Queue()
        self.proceso = None
        self.ruta_video = None
        self.carpeta_salida = None
        self.finalizo = False
        self.ultimo_resumen = {}

        self.construir_ventana()
        self.revisar_cola()
        ventana.protocol("WM_DELETE_WINDOW", self.cerrar)

    def construir_ventana(self):
        self.ventana.title("AgroScan - Procesamiento (prototipo)")
        self.ventana.geometry("860x580")
        self.ventana.configure(bg=FONDO)

        estilo = ttk.Style()
        estilo.theme_use("clam")
        estilo.configure(
            "Verde.Horizontal.TProgressbar",
            troughcolor="#e5e7eb", background=VERDE, bordercolor="#e5e7eb",
            lightcolor=VERDE, darkcolor=VERDE,
        )

        tk.Label(
            self.ventana, text="Procesamiento", bg=FONDO, fg=TEXTO,
            font=("Segoe UI", 16, "bold"), anchor="w",
        ).pack(fill="x", padx=24, pady=(18, 8))

        tarjeta = tk.Frame(
            self.ventana, bg="white", highlightbackground="#d1d5db", highlightthickness=1
        )
        tarjeta.pack(fill="x", padx=24, pady=8)
        interior = tk.Frame(tarjeta, bg="white")
        interior.pack(fill="x", padx=22, pady=18)

        etiqueta(interior, "Preprocesamiento de imágenes", tamano=12, negrita=True).pack(fill="x")
        descripcion = etiqueta(
            interior,
            "Extrae del video los frames necesarios para el mosaico, analiza su calidad, "
            "corrige solo lo necesario y guarda las imágenes con sus metadatos dentro.",
            color=GRIS,
        )
        descripcion.config(wraplength=770)
        descripcion.pack(fill="x", pady=(2, 12))

        fila_video = tk.Frame(interior, bg="white")
        fila_video.pack(fill="x", pady=4)
        etiqueta(fila_video, "Video:", negrita=True).pack(side="left")
        self.texto_video = etiqueta(fila_video, "Ninguno seleccionado", color=GRIS)
        self.texto_video.pack(side="left", padx=8)
        boton(fila_video, "Elegir video...", self.elegir_video, principal=False).pack(side="right")

        fila_salida = tk.Frame(interior, bg="white")
        fila_salida.pack(fill="x", pady=4)
        etiqueta(fila_salida, "Resultados en:", negrita=True).pack(side="left")
        self.texto_salida = etiqueta(fila_salida, "-", color=GRIS)
        self.texto_salida.pack(side="left", padx=8)

        fila_servidor = tk.Frame(interior, bg="white")
        fila_servidor.pack(fill="x", pady=4)
        self.enviar_al_servidor = tk.BooleanVar(value=False)
        tk.Checkbutton(
            fila_servidor, text="Enviar al servidor:", variable=self.enviar_al_servidor,
            bg="white", activebackground="white", fg=TEXTO,
            font=("Segoe UI", 10, "bold"),
        ).pack(side="left")
        self.entrada_servidor = tk.Entry(
            fila_servidor, width=34, font=("Segoe UI", 10),
            relief="flat", highlightthickness=1, highlightbackground="#d1d5db",
        )
        self.entrada_servidor.insert(0, "http://127.0.0.1:5000")
        self.entrada_servidor.pack(side="left", padx=8)

        self.boton_iniciar = boton(interior, "Iniciar preprocesamiento", self.iniciar)
        self.boton_iniciar.pack(anchor="w", pady=(14, 8))
        self.boton_iniciar.config(state="disabled")

        self.texto_etapa = etiqueta(interior, "Listo para iniciar", negrita=True)
        self.texto_etapa.pack(fill="x", pady=(8, 4))
        self.barra = ttk.Progressbar(
            interior, style="Verde.Horizontal.TProgressbar",
            mode="determinate", maximum=100, length=770,
        )
        self.barra.pack(fill="x")
        self.texto_porcentaje = etiqueta(interior, "0 %", color=GRIS)
        self.texto_porcentaje.pack(anchor="e", pady=(2, 0))

        self.marco_resultado = tk.Frame(interior, bg="white")
        self.marco_resultado.pack(fill="x", pady=(10, 0))

        self.marco_botones = tk.Frame(interior, bg="white")
        self.marco_botones.pack(fill="x", pady=(12, 0))
        self.boton_abrir = boton(
            self.marco_botones, "Abrir carpeta de resultados", self.abrir_carpeta, principal=False
        )
        self.boton_reintentar = boton(
            self.marco_botones, "Reintentar envío", self.reintentar_envio, principal=False
        )

        self.texto_error = etiqueta(interior, "", color="#b91c1c")
        self.texto_error.config(wraplength=770)
        self.texto_error.pack(fill="x", pady=(8, 0))

    def elegir_video(self):
        ruta = filedialog.askopenfilename(
            title="Elegir video", filetypes=[("Videos MP4", "*.mp4 *.MP4")]
        )
        if not ruta:
            return
        self.ruta_video = Path(ruta)
        self.carpeta_salida = carpeta_salida_para(self.ruta_video)
        self.texto_video.config(text=self.ruta_video.name, fg=TEXTO)
        self.texto_salida.config(text=str(self.carpeta_salida))
        self.boton_iniciar.config(state="normal")
        self.actualizar_botones()

    def iniciar(self):
        comando = [
            sys.executable, str(RAIZ / "main.py"),
            "--video", str(self.ruta_video),
            "--salida", str(self.carpeta_salida),
            "--json",
        ]
        if self.enviar_al_servidor.get():
            comando += ["--servidor", self.entrada_servidor.get().strip()]

        self.mostrar_progreso(0, "Preparando video")
        self.lanzar(comando, limpiar_resultado=True)

    def reintentar_envio(self):
        comando = [
            sys.executable, str(RAIZ / "main.py"),
            "--salida", str(self.carpeta_salida),
            "--servidor", self.entrada_servidor.get().strip(),
            "--solo-sincronizar",
            "--json",
        ]
        self.mostrar_progreso(0, "Sincronizando archivos")
        self.lanzar(comando, limpiar_resultado=False)

    def abrir_carpeta(self):
        os.startfile(str(self.carpeta_salida))

    def cerrar(self):
        # Si el procesamiento sigue en marcha, detenerlo para no dejarlo huérfano
        if self.proceso is not None and self.proceso.poll() is None:
            self.proceso.terminate()
        self.ventana.destroy()

    # ---------- Hilo que lanza main.py y lee sus mensajes ----------

    def lanzar(self, comando, limpiar_resultado):
        self.finalizo = False
        self.boton_iniciar.config(state="disabled")
        self.boton_abrir.pack_forget()
        self.boton_reintentar.pack_forget()
        self.texto_error.config(text="")

        if limpiar_resultado:
            self.ultimo_resumen = {}
            for elemento in self.marco_resultado.winfo_children():
                elemento.destroy()

        hilo = threading.Thread(target=self.ejecutar, args=(comando,), daemon=True)
        hilo.start()

    def ejecutar(self, comando):
        try:
            self.proceso = subprocess.Popen(comando, stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL, text=True, encoding="utf-8", cwd=str(RAIZ),
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            for linea in self.proceso.stdout:
                try:
                    self.cola.put(json.loads(linea))
                except ValueError:
                    pass    # una línea que no es JSON se ignora
            self.proceso.wait()
            self.cola.put({"tipo": "termino", "codigo": self.proceso.returncode})
        except Exception as error:
            self.cola.put({"tipo": "error", "mensaje": str(error)})

    def revisar_cola(self):
        while True:
            try:
                evento = self.cola.get_nowait()
            except queue.Empty:
                break
            self.procesar_evento(evento)
        self.ventana.after(100, self.revisar_cola)

    def procesar_evento(self, evento):
        tipo = evento.get("tipo")

        if tipo == "progreso":
            self.mostrar_progreso(evento["porcentaje"], evento["etapa"])

        elif tipo == "fin":
            self.finalizo = True
            resumen = evento["resumen"]
            if "imagenes_finales" in resumen:
                self.mostrar_progreso(100, "Preprocesamiento completado")
            else:
                self.mostrar_progreso(100, "Sincronización completada")
            self.ultimo_resumen.update(resumen)
            self.mostrar_resultado(self.ultimo_resumen)
            self.actualizar_botones()

        elif tipo == "error":
            self.finalizo = True
            self.texto_error.config(text=f"Error: {evento['mensaje']}")
            self.actualizar_botones()

        elif tipo == "termino" and not self.finalizo:
            self.texto_error.config(
                text=f"El proceso terminó de forma inesperada (código {evento['codigo']})"
            )
            self.actualizar_botones()

    # ---------- Actualizar lo que se ve ----------

    def mostrar_progreso(self, porcentaje, etapa):
        self.barra["value"] = porcentaje
        self.texto_etapa.config(text=etapa)
        self.texto_porcentaje.config(text=f"{porcentaje} %")

    def agregar_indicador(self, texto, alerta=False):
        fondo, color = (AMBAR_CLARO, AMBAR) if alerta else (VERDE_CLARO, VERDE)
        tk.Label(
            self.marco_resultado, text=texto, bg=fondo, fg=color,
            font=("Segoe UI", 9, "bold"), padx=12, pady=4,
        ).pack(side="left", padx=(0, 8))

    def mostrar_resultado(self, resumen):
        for elemento in self.marco_resultado.winfo_children():
            elemento.destroy()

        if "imagenes_finales" in resumen:
            self.agregar_indicador(f"{resumen['imagenes_finales']} imágenes")
            self.agregar_indicador(f"{resumen['modificadas']} modificadas")
            self.agregar_indicador(f"{resumen['con_advertencias']} con advertencias")
            if resumen["con_pendientes"] > 0:
                self.agregar_indicador(f"{resumen['con_pendientes']} con problemas pendientes", True)
            if resumen["plan_b"]:
                self.agregar_indicador("Plan B: frames por intervalo fijo", True)

        sincronizacion = resumen.get("sincronizacion")
        if sincronizacion:
            self.agregar_indicador(f"{sincronizacion['enviadas']} enviadas")
            if sincronizacion["pendientes"] > 0:
                self.agregar_indicador(f"{sincronizacion['pendientes']} pendientes de envío", True)
            if sincronizacion["con_error"] > 0:
                self.agregar_indicador(f"{sincronizacion['con_error']} con error de envío", True)
            if sincronizacion["sin_conexion"]:
                self.agregar_indicador("Sin conexión con el servidor", True)

    def hay_pendientes_de_envio(self):
        if self.carpeta_salida is None:
            return False
        ruta = self.carpeta_salida / config.ARCHIVO_ESTADO
        if not ruta.exists():
            return False
        try:
            with open(ruta, "r", encoding="utf-8") as archivo:
                estado = json.load(archivo)
        except (OSError, ValueError):
            return False
        for registro in estado.get("imagenes", {}).values():
            if registro["estado"] != "enviada":
                return True
        return False

    def actualizar_botones(self):
        if self.ruta_video is not None:
            self.boton_iniciar.config(state="normal")
        self.boton_abrir.pack_forget()
        self.boton_reintentar.pack_forget()

        if self.ultimo_resumen:
            self.boton_abrir.pack(side="left", padx=(0, 8))
        if self.hay_pendientes_de_envio():
            self.boton_reintentar.pack(side="left")


def main():
    ventana = tk.Tk()
    TarjetaProcesamiento(ventana)
    ventana.mainloop()


if __name__ == "__main__":
    main()