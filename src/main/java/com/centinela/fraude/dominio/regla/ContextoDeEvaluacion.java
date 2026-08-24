package com.centinela.fraude.dominio.regla;

import com.centinela.fraude.dominio.compartido.Invariantes;
import com.centinela.fraude.dominio.modelo.PerfilDeComportamiento;
import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.modelo.vo.ClaveDeVelocidad;
import com.centinela.fraude.dominio.modelo.vo.Geolocalizacion;
import com.centinela.fraude.dominio.modelo.vo.IdDispositivo;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Todo lo que una regla puede mirar, reunido antes de ejecutar ninguna.
 *
 * <h2>Por qué las reglas no hacen E/S</h2>
 *
 * El caso de uso reúne el perfil y los contadores <em>una vez</em>, en paralelo, y luego ejecuta
 * las reglas sobre este contexto ya materializado. Si cada regla consultara su propio dato:
 *
 * <ul>
 *   <li>la latencia sería la suma de todas las consultas en vez del máximo;
 *   <li>dos reglas que miran el mismo contador harían dos viajes;
 *   <li>el presupuesto de latencia sería imposible de acotar, porque dependería de cuántas
 *       reglas se dispararan;
 *   <li>y probar una regla exigiría simular su fuente de datos.
 * </ul>
 *
 * Con este diseño cada regla es una <strong>función pura</strong> del contexto: se prueba sin
 * mocks, en microsegundos, y el conjunto entero se puede reejecutar sobre datos históricos para
 * calibrar sin tocar infraestructura.
 *
 * @param contadores contadores ya resueltos; una clave ausente significa "no se pudo obtener",
 *     no "vale cero", y las reglas deben tratarlo como {@link ResultadoDeRegla.Degradada}
 */
public record ContextoDeEvaluacion(
        Transaccion transaccion,
        PerfilDeComportamiento perfil,
        Map<ClaveDeVelocidad, Long> contadores,
        PoliticaDeRiesgo politica,
        Instant evaluadoEn) {

    public ContextoDeEvaluacion {
        Invariantes.noNulo(transaccion, "contexto.transaccion");
        Invariantes.noNulo(perfil, "contexto.perfil");
        Invariantes.noNulo(politica, "contexto.politica");
        Invariantes.noNulo(evaluadoEn, "contexto.evaluadoEn");
        contadores = Map.copyOf(Invariantes.noNulo(contadores, "contexto.contadores"));

        Invariantes.exigir(
                perfil.cliente().equals(transaccion.cliente()),
                "El perfil pertenece a otro cliente que el de la transacción");
    }

    /**
     * Valor de un contador.
     *
     * @return vacío si el contador no se pudo obtener; nunca cero por defecto
     */
    public OptionalLong contador(ClaveDeVelocidad clave) {
        Long valor = contadores.get(Invariantes.noNulo(clave, "clave"));
        return valor == null ? OptionalLong.empty() : OptionalLong.of(valor);
    }

    /** Atajos de lectura frecuente, para que las reglas no encadenen tres accesores. */
    public Optional<IdDispositivo> dispositivo() {
        return transaccion.dispositivo();
    }

    public Optional<Geolocalizacion> origen() {
        return transaccion.origen();
    }
}
