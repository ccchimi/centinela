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
     * Persiste el perfil recalculado tras la transacción.
     *
     * <p>Se espera implementación idempotente y con escritura condicionada por
     * {@code actualizadoEn}, para que dos evaluaciones concurrentes del mismo cliente no se
     * pisen los agregados.
     */
    void guardar(PerfilDeComportamiento perfil);
}
