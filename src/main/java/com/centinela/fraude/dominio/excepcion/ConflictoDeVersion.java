package com.centinela.fraude.dominio.excepcion;

import java.io.Serial;

/**
 * Otro proceso modificó el agregado entre la lectura y la escritura.
 *
 * <p>Es el resultado normal del bloqueo optimista, no una avería: dos evaluaciones concurrentes
 * del mismo cliente leyeron el mismo perfil y sólo una puede escribir. Quien la recibe decide si
 * releer y reintentar o abandonar la actualización.
 *
 * <p>Se lanza sin traza de pila: bajo carga, un cliente activo genera conflictos con regularidad
 * y rellenar la traza cada vez sería un coste sin contrapartida.
 */
public final class ConflictoDeVersion extends ErrorDeDominio {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String CODIGO = "DOMINIO_CONFLICTO_DE_VERSION";

    private final long versionEsperada;

    private ConflictoDeVersion(String agregado, String identificador, long versionEsperada) {
        super(
                CODIGO,
                "El %s '%s' cambió desde que se leyó (se esperaba la versión %d)"
                        .formatted(agregado, identificador, versionEsperada),
                null,
                false);
        this.versionEsperada = versionEsperada;
    }

    public static ConflictoDeVersion de(String agregado, String identificador, long versionEsperada) {
        return new ConflictoDeVersion(agregado, identificador, versionEsperada);
    }

    public long versionEsperada() {
        return versionEsperada;
    }
}
