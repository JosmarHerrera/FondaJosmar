package tecnm.itch.fonda.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import tecnm.itch.reservaciones.dto.EmpleadoDto;

@FeignClient(name = "reservaciones", contextId = "empleadoClient")
public interface EmpleadoClient {

	@GetMapping("/api/atender/venta/{idVenta}")
	EmpleadoDto findEmpleadoByVentaId(@PathVariable("idVenta") Integer idVenta);
	
	@GetMapping("/api/empleado/por-usuario/{idUsuario}")
    EmpleadoDto findEmpleadoByUsuarioId(@PathVariable("idUsuario") Integer idUsuario);
}