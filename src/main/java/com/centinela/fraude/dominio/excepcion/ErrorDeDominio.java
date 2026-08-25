package com.centinela.fraude.dominio.excepcion;

/**
 * Raíz sellada de todos los errores que el núcleo del hexágono puede propagar.
 *
 * <p>Al ser {@code sealed}, el manejador global de excepciones del adaptador de entrada puede
 * recorrerla con un {@code switch} exhaustivo sobre patrones: el compilador garantiza que si
 * mañana se añade un subtipo, el mapeo a respuestas HTTP deja de compilar en vez de degradarse
 * silenciosamente a un 500.
 *
 * <p>Cada error lleva un {@link #codigo()} estable, pensado para ser consumido por máquinas
 * (dashboards, alertas, contratos de API) sin depender del texto del mensaje.
 */
public sealed abstract class ErrorDeDominio extends RuntimeException
        permits ViolacionDeInvariante, ReglaDeNegocioIncumplida, RecursoDeDominioNoEncontrado, ConflictoDeVersion {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    private final String codigo;

    /**
     * @param stackTraceEscribible false para errores de altísima frecuencia (validación de
     *     entrada no confiable). Rellenar la traza es la parte cara de construir una excepción y
     *     a 10k TPS se nota; el {@link #codigo()} y el mensaje bastan para diagnosticar.
     */
    protected ErrorDeDominio(String codigo, String mensaje, Throwable causa, boolean stackTraceEscribible) {
        super(mensaje, causa, false, stackTraceEscribible);
        this.codigo = codigo;
    }

    public String codigo() {
        return codigo;
    }

    @Override
    public String toString() {
        return "%s[%s] %s".formatted(getClass().getSimpleName(), codigo, getMessage());
    }
}
