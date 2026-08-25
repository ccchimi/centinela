package com.centinela.fraude.infraestructura.adaptador.salida.redis;

import com.centinela.fraude.dominio.modelo.vo.ClaveDeVelocidad;
import com.centinela.fraude.dominio.puerto.salida.ContadorDeVelocidad;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Adaptador de {@link ContadorDeVelocidad} sobre ElastiCache (Valkey/Redis).
 *
 * <h2>Por qué un script Lua y no INCR + EXPIRE</h2>
 *
 * El puerto exige que incrementar y leer sean atómicos. La versión ingenua —{@code INCR} y
 * luego {@code EXPIRE} si el resultado es 1— tiene una ventana en la que el proceso puede caer
 * entre las dos órdenes y dejar la clave <strong>sin expiración, para siempre</strong>. Ese
 * contador nunca volvería a bajar del umbral y la regla de ráfaga dispararía en cada operación
 * de ese cliente hasta que alguien lo borrara a mano. Redis ejecuta el script entero de forma
 * atómica y el problema desaparece.
 *
 * <h2>Ventana fija, no deslizante</h2>
 *
 * La expiración se fija en el primer incremento, así que la ventana es fija: un atacante que
 * conozca el umbral puede repartir la ráfaga a caballo de dos ventanas y colar hasta el doble.
 * Se acepta a cambio de una sola orden por evaluación y de memoria constante por clave; una
 * ventana deslizante exigiría un sorted set por clave, con el coste de memoria y de poda que eso
 * implica. Está anotado como mejora en el README.
 */
public final class ContadorDeVelocidadRedis implements ContadorDeVelocidad {

    /** Prefijo con versión: permite invalidar todos los contadores cambiando el prefijo. */
    private static final String PREFIJO = "centinela:v1:vel:";

    private static final RedisScript<Long> INCREMENTAR_Y_EXPIRAR = new DefaultRedisScript<>(
            """
            local cuenta = redis.call('INCR', KEYS[1])
            if cuenta == 1 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return cuenta
            """,
            Long.class);

    private final StringRedisTemplate plantilla;

    public ContadorDeVelocidadRedis(StringRedisTemplate plantilla) {
        this.plantilla = plantilla;
    }

    @Override
    public long incrementarYContar(ClaveDeVelocidad clave, Duration ventana) {
        Long cuenta = plantilla.execute(
                INCREMENTAR_Y_EXPIRAR, List.of(claveRedis(clave)), Long.toString(ventana.toMillis()));
        // El script siempre devuelve un entero; un null aquí solo puede venir de un fallo de
        // transporte que Lettuce ya habría propagado como excepción. Se defiende igualmente
        // porque devolver 0 en silencio equivaldría a apagar la regla de ráfaga.
        if (cuenta == null) {
            throw new IllegalStateException("El contador de velocidad no devolvió resultado para " + clave);
        }
        return cuenta;
    }

    @Override
    public long contar(ClaveDeVelocidad clave, Duration ventana) {
        String valor = plantilla.opsForValue().get(claveRedis(clave));
        // Clave ausente significa ventana vacía: aquí sí, cero es la respuesta correcta, porque
        // la consulta llegó a Redis y Redis contestó "no hay nada".
        return valor == null ? 0L : Long.parseLong(valor);
    }

    private static String claveRedis(ClaveDeVelocidad clave) {
        return PREFIJO + clave.dimension().name() + ':' + clave.valor();
    }
}
