package tecnm.itch.fonda.service.implement;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import feign.FeignException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tecnm.itch.fonda.ResourceNotFoundException;
import tecnm.itch.fonda.client.AtenderClient;
import tecnm.itch.fonda.client.ClienteClient;
import tecnm.itch.fonda.client.EmpleadoClient;
import tecnm.itch.fonda.client.ReservaClient;
import tecnm.itch.fonda.dto.AtenderDto;
import tecnm.itch.fonda.dto.VentaDto;
import tecnm.itch.fonda.dto.VentaResponseDto;
import tecnm.itch.fonda.entity.DetalleVenta;
import tecnm.itch.fonda.entity.Venta;
import tecnm.itch.fonda.mapper.VentaMapper;
import tecnm.itch.fonda.repository.ProductoRepository;
import tecnm.itch.fonda.repository.VentaRepository;
import tecnm.itch.fonda.service.VentaService;

@Slf4j
@AllArgsConstructor
@Service
public class VentaServiceImplement implements VentaService {

    private final VentaRepository ventaRepository;
    private final ProductoRepository productoRepository;
    private final ClienteClient cliente;
    private final EmpleadoClient empleadoClient;
    private final AtenderClient atenderClient;
    private final ReservaClient reservaClient;

    // =========================================================
    // CREAR VENTA
    // =========================================================
    @Override
    @Transactional
    public VentaDto createVenta(VentaDto ventaDto) {
        if (ventaDto.getId_cliente() == null) {
            throw new IllegalArgumentException("El id_cliente es obligatorio");
        }

        // 1) Validar cliente vía Feign
        try {
            cliente.getClienteById(ventaDto.getId_cliente());
        } catch (FeignException.NotFound nf) {
            throw new ResourceNotFoundException("Cliente no existe: " + ventaDto.getId_cliente());
        } catch (FeignException fe) {
            throw new IllegalStateException("Error al consultar el microservicio de Cliente: " + fe.getMessage());
        }

        // 2) Crear entidad Venta
        Venta venta = new Venta();
        venta.setIdCliente(ventaDto.getId_cliente());
        venta.setIdEmpleado(ventaDto.getId_empleado());
        venta.setIdReserva(ventaDto.getId_reserva());
        venta.setDetalles(new java.util.ArrayList<>());

        if (ventaDto.getDetalles() == null || ventaDto.getDetalles().isEmpty()) {
            throw new IllegalArgumentException("La venta debe tener al menos un detalle de producto.");
        }

        double total = 0.0;

        // 3) Construir cada DetalleVenta usando los DetalleVentaDto
        for (tecnm.itch.fonda.dto.DetalleVentaDto detDto : ventaDto.getDetalles()) {

            // 1) Sacar el id del producto
            Integer tmpIdProd = detDto.getId_producto();

            if (tmpIdProd == null && detDto.getProducto() != null) {
                tmpIdProd = detDto.getProducto().getId_producto();
                detDto.setId_producto(tmpIdProd); // sincronizamos
            }

            if (tmpIdProd == null) {
                throw new IllegalArgumentException("Cada detalle debe tener id_producto");
            }

            // 2) Variable efectivamente final para usar en el lambda
            final Integer idProd = tmpIdProd;

            var producto = productoRepository.findById(idProd)
                    .orElseThrow(() -> new ResourceNotFoundException("Producto no existe: " + idProd));

            DetalleVenta det = new DetalleVenta();
            det.setProducto(producto);
            det.setVenta(venta);

            // cantidad
            Integer cant = detDto.getCantidad();
            if (cant == null || cant <= 0) {
                cant = 1;
            }
            det.setCantidad(cant);

            // precio unitario
            Double precio = detDto.getPrecio_unitario();
            if (precio == null || precio <= 0) {
                precio = producto.getPrecio();
            }
            det.setPrecioUnitario(precio);

            total += cant * precio;

            venta.getDetalles().add(det);
        }

        // 4) Total calculado en backend
        venta.setTotal(total);

        // ✅ Guardar venta
        Venta guardada = ventaRepository.save(venta);
        log.info("✅ Venta guardada con id {} y total {}", guardada.getIdVenta(), guardada.getTotal());

        // ✅ Registrar atención, pero que NO reviente la transacción si falla
        if (ventaDto.getId_empleado() != null) {
            AtenderDto atenderInfo = new AtenderDto();
            atenderInfo.setIdEmpleado(ventaDto.getId_empleado());
            atenderInfo.setIdVenta(guardada.getIdVenta());

            try {
                atenderClient.crearAtender(atenderInfo);
                log.info("✅ Atención registrada correctamente en microservicio reservaciones.");
            } catch (FeignException e) {
                log.warn("⚠️ No se pudo registrar la atención, pero la venta se mantiene. Error: {}",
                        e.getMessage());
            }
        }

        // ✅ Confirmar reserva sin reventar
        if (guardada.getIdReserva() != null) {
            try {
                log.info("Confirmando reserva con ID: {}", guardada.getIdReserva());
                reservaClient.confirmarReserva(guardada.getIdReserva());
                log.info("Reserva confirmada exitosamente.");
            } catch (FeignException e) {
                log.warn("⚠️ No se pudo confirmar la reserva {}, pero la venta se mantiene. Error: {}",
                        guardada.getIdReserva(), e.getMessage());
            }
        }

        return VentaMapper.mapToVentaDto(guardada);
    }

    // =========================================================
    // ACTUALIZAR VENTA
    // =========================================================
    @Override
    @Transactional
    public VentaDto updateVenta(Integer ventaId, VentaDto updateVenta) {

        Venta venta = ventaRepository.findById(ventaId)
                .orElseThrow(() -> new ResourceNotFoundException("Venta no encontrada con id: " + ventaId));

        // 1. Actualizar cliente si cambia
        if (updateVenta.getId_cliente() != null &&
                !updateVenta.getId_cliente().equals(venta.getIdCliente())) {

            try {
                cliente.getClienteById(updateVenta.getId_cliente());
            } catch (FeignException.NotFound nf) {
                throw new ResourceNotFoundException("Cliente no existe: " + updateVenta.getId_cliente());
            }

            venta.setIdCliente(updateVenta.getId_cliente());
        }

        // 2. Actualizar empleado / reserva
        if (updateVenta.getId_empleado() != null) {
            venta.setIdEmpleado(updateVenta.getId_empleado());
        }

        venta.setIdReserva(updateVenta.getId_reserva());

        // 3. REEMPLAZAR DETALLES Y RECALCULAR TOTAL
        if (updateVenta.getDetalles() == null || updateVenta.getDetalles().isEmpty()) {
            throw new IllegalArgumentException("La venta debe tener al menos un detalle.");
        }

        // Borrar detalles actuales
        venta.getDetalles().clear();

        double total = 0.0;

        for (tecnm.itch.fonda.dto.DetalleVentaDto detDto : updateVenta.getDetalles()) {

            // === OBTENER ID DEL PRODUCTO SIN ROMPER EL LAMBDA ===
            Integer tmpIdProducto = detDto.getId_producto();

            if (tmpIdProducto == null && detDto.getProducto() != null) {
                tmpIdProducto = detDto.getProducto().getId_producto();
            }

            if (tmpIdProducto == null) {
                throw new IllegalArgumentException("Cada detalle debe tener id_producto.");
            }

            // ahora sí, variable effectively final para el lambda
            final Integer idProducto = tmpIdProducto;

            // === OBTENER PRODUCTO ===
            var producto = productoRepository.findById(idProducto)
                    .orElseThrow(() ->
                            new ResourceNotFoundException("Producto no existe: " + idProducto));

            DetalleVenta det = new DetalleVenta();
            det.setVenta(venta);
            det.setProducto(producto);

            int cant = (detDto.getCantidad() == null || detDto.getCantidad() <= 0)
                    ? 1
                    : detDto.getCantidad();

            det.setCantidad(cant);

            double precio = (detDto.getPrecio_unitario() == null || detDto.getPrecio_unitario() <= 0)
                    ? producto.getPrecio()
                    : detDto.getPrecio_unitario();

            det.setPrecioUnitario(precio);

            total += cant * precio;

            venta.getDetalles().add(det);
        }

        venta.setTotal(total);

        Venta guardada = ventaRepository.save(venta);

        return VentaMapper.mapToVentaDto(guardada);
    }

    // =========================================================
    // CONSULTAS
    // =========================================================
    @Override
    @Transactional(readOnly = true)
    public VentaDto getVentaById(Integer ventaId) {
        Venta venta = ventaRepository.findById(ventaId)
                .orElseThrow(() -> new ResourceNotFoundException("Venta no encontrada con id: " + ventaId));

        VentaDto ventaDto = VentaMapper.mapToVentaDto(venta);

        try {
            var clienteDto = cliente.getClienteById(venta.getIdCliente());
            ventaDto.setCliente(clienteDto);
        } catch (FeignException e) {
            log.warn("No se pudo obtener el cliente {}: {}", venta.getIdCliente(), e.getMessage());
        }

        try {
            var empleadoDto = empleadoClient.findEmpleadoByVentaId(venta.getIdVenta());
            ventaDto.setEmpleado(empleadoDto);
        } catch (FeignException e) {
            log.warn("No se pudo obtener el mesero para la venta {}: {}", venta.getIdVenta(), e.getMessage());
            ventaDto.setEmpleado(null);
        }

        return ventaDto;
    }

    @Override
    @Transactional(readOnly = true)
    public List<VentaResponseDto> getAllVentas() {
        List<Venta> ventas = ventaRepository.findAll();
        return ventas.stream().map(this::mapToVentaResponseDto).collect(Collectors.toList());
    }

    private VentaResponseDto mapToVentaResponseDto(Venta venta) {
        VentaResponseDto dto = new VentaResponseDto();
        dto.setId_venta(venta.getIdVenta());
        dto.setFecha_venta(venta.getFechaVenta());
        dto.setTotal(venta.getTotal());
        dto.setId_reserva(venta.getIdReserva());

        try {
            var clienteData = cliente.getClienteById(venta.getIdCliente());
            if (clienteData != null) {
                VentaResponseDto.ClienteInfo clienteInfo = new VentaResponseDto.ClienteInfo();
                clienteInfo.setNombre_cliente(clienteData.getNombreCliente());
                dto.setCliente(clienteInfo);
            }
        } catch (FeignException e) {
            log.warn("No se pudo obtener cliente {}: {}", venta.getIdCliente(), e.getMessage());
        }

        try {
            var empleadoData = empleadoClient.findEmpleadoByVentaId(venta.getIdVenta());
            if (empleadoData != null) {
                VentaResponseDto.EmpleadoInfo empleadoInfo = new VentaResponseDto.EmpleadoInfo();
                empleadoInfo.setNombre(empleadoData.getNombre());
                dto.setEmpleado(empleadoInfo);
            }
        } catch (FeignException e) {
            log.warn("No se pudo obtener mesero para venta {}: {}", venta.getIdVenta(), e.getMessage());
        }

        return dto;
    }

    // =========================================================
    // BORRAR
    // =========================================================
    @Override
    @Transactional
    public void deleteVenta(Integer ventaId) {
        if (!ventaRepository.existsById(ventaId)) {
            throw new ResourceNotFoundException("Venta no encontrada con id: " + ventaId);
        }
        ventaRepository.deleteById(ventaId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VentaResponseDto> findVentasByFecha(String fecha) {
        List<Venta> ventas = ventaRepository.findVentasByFecha(fecha);
        return ventas.stream().map(this::mapToVentaResponseDto).collect(Collectors.toList());
    }
}
