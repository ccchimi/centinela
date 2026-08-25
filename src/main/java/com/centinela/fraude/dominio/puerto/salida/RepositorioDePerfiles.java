package com.centinela.fraude.dominio.puerto.salida;

import com.centinela.fraude.dominio.modelo.PerfilDeComportamiento;
import com.centinela.fraude.dominio.modelo.vo.IdCliente;
import java.util.Optional;

/**
 * Puerto de salida hacia el almacén del perfil de comportamiento del cliente.
 *
 * <p>Se implementará sobre DynamoDB con lectura por clave de partición. El puerto habla de
 * perfiles, no de tablas ni de items: si mañana el almacén cambia, el núcleo no se entera.
 *
 * <p>Devuelve {@link Optional} y no lanza excepción cuando no hay perfil: un cliente nuevo es
 * un caso de negocio esperado.
 */
public interface RepositorioDePerfiles {

    /**
     * @return el perfil del cliente, o vacío si aún no tiene historial
     */
    Optional<PerfilDeComportamiento> buscarPor(IdCliente cliente);

    /**
     * Persiste el perfil recalculado tras la transacción, con bloqueo optimista.
     *
     * <p>La implementación debe condicionar la escritura a que la fila siga en la versión
     * anterior a la del perfil recibido ({@code perfil.version() - 1}), o a que no exista
     * todavía. Sin esa condición, dos evaluaciones concurrentes del mismo cliente leerían el
     * mismo perfil, calcularían dos versiones a partir de la misma base y la última en escribir
     * borraría el aprendizaje de la primera: los agregados retrocederían sin dejar rastro.
     *
     * <p>Se eligió un contador de versión y no la marca temporal porque dos evaluaciones del
     * mismo cliente pueden caer en el mismo milisegundo, y entonces una comparación por fecha no
     * distingue nada.
     *
     * @throws com.centinela.fraude.dominio.excepcion.ConflictoDeVersion si otra escritura ganó
     *     la carrera. No es una avería: quien la reciba decide si releer y reintentar o
     *     abandonar la actualización
     */
    void guardar(PerfilDeComportamiento perfil);
}
