package com.centinela.fraude.dominio.excepcion;

/**
 * Se referenció una entidad del dominio que no existe (perfil, evaluación previa, lista de
 * control). Semánticamente equivale a un 404.
 *
 * <p>Ojo: la ausencia de perfil de comportamiento para un cliente <em>no</em> es este error.
 * Un cliente nuevo es un caso de negocio normal y se modela con
 * {@code PerfilDeComportamiento.nuevoPara(cliente)}, no con una excepción.
 */
public final class RecursoDeDominioNoEncontrado extends ErrorDeDominio {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public static final String CODIGO = "DOMINIO_RECURSO_NO_ENCONTRADO";

    private final String tipo;
    private final String identificador;

    private RecursoDeDominioNoEncontrado(String tipo, String identificador) {
        super(CODIGO, "No existe %s con identificador '%s'".formatted(tipo, identificador), null, true);
        this.tipo = tipo;
        this.identificador = identificador;
    }

    public static RecursoDeDominioNoEncontrado de(String tipo, String identificador) {
        return new RecursoDeDominioNoEncontrado(tipo, identificador);
    }

    public String tipo() {
        return tipo;
    }

    public String identificador() {
        return identificador;
    }
}
