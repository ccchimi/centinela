package com.centinela.fraude.dominio.compartido;

import com.centinela.fraude.dominio.excepcion.ViolacionDeInvariante;

/**
 * Guardas de validación para los constructores compactos del dominio.
 *
 * <p>Deliberadamente no usamos {@code jakarta.validation} aquí: el núcleo del hexágono no
 * conoce frameworks. Las anotaciones de Bean Validation viven en los DTO de los adaptadores
 * de entrada, donde protegen la frontera; estas guardas protegen el <em>modelo</em>, y lo
 * hacen de forma que un objeto de dominio nunca pueda existir en estado inválido.
 *
 * <p>Nota sobre formateo: JEP 430/459/465 (String Templates) fue retirado del JDK y no existe
 * en ninguna versión actual. El equivalente idiomático es {@link String#formatted(Object...)}.
 */
public final class Invariantes {

    private Invariantes() {
        throw new AssertionError("Clase de utilidades, no instanciable");
    }

    /** Exige que se cumpla una condición de negocio; si no, aborta la construcción. */
    public static void exigir(boolean condicion, String plantilla, Object... argumentos) {
        if (!condicion) {
            throw ViolacionDeInvariante.de(plantilla.formatted(argumentos));
        }
    }

    /** Exige que un valor obligatorio esté presente. */
    public static <T> T noNulo(T valor, String campo) {
        if (valor == null) {
            throw ViolacionDeInvariante.de("'%s' es obligatorio".formatted(campo));
        }
        return valor;
    }

    /** Exige texto con contenido y lo devuelve recortado. */
    public static String textoConContenido(String valor, String campo) {
        noNulo(valor, campo);
        String recortado = valor.strip();
        exigir(!recortado.isEmpty(), "'%s' no puede estar en blanco", campo);
        return recortado;
    }

    /** Exige que el texto (ya recortado) no supere una longitud máxima. */
    public static String textoConContenido(String valor, String campo, int longitudMaxima) {
        String recortado = textoConContenido(valor, campo);
        exigir(
                recortado.length() <= longitudMaxima,
                "'%s' supera la longitud máxima de %d caracteres (recibidos %d)",
                campo,
                longitudMaxima,
                recortado.length());
        return recortado;
    }

    /** Exige que el valor caiga dentro de un rango cerrado. */
    public static int enRango(int valor, int minimo, int maximo, String campo) {
        exigir(valor >= minimo && valor <= maximo, "'%s' debe estar entre %d y %d, pero fue %d", campo, minimo, maximo, valor);
        return valor;
    }

    /** Exige que el valor caiga dentro de un rango cerrado. */
    public static double enRango(double valor, double minimo, double maximo, String campo) {
        exigir(
                Double.isFinite(valor) && valor >= minimo && valor <= maximo,
                "'%s' debe estar entre %s y %s, pero fue %s",
                campo,
                minimo,
                maximo,
                valor);
        return valor;
    }

    /** Exige un código de país ISO-3166-1 alfa-2 y lo normaliza a mayúsculas. */
    public static String paisIso2(String valor, String campo) {
        String normalizado = textoConContenido(valor, campo).toUpperCase(java.util.Locale.ROOT);
        exigir(
                normalizado.length() == 2 && normalizado.chars().allMatch(Character::isLetter),
                "'%s' debe ser un código ISO-3166-1 alfa-2, pero fue '%s'",
                campo,
                normalizado);
        return normalizado;
    }

    /** Exige que el texto contenga únicamente dígitos y tenga la longitud exacta indicada. */
    public static String digitos(String valor, String campo, int longitudExacta) {
        String normalizado = textoConContenido(valor, campo);
        exigir(
                normalizado.length() == longitudExacta && normalizado.chars().allMatch(Character::isDigit),
                "'%s' debe tener exactamente %d dígitos",
                campo,
                longitudExacta);
        return normalizado;
    }
}
