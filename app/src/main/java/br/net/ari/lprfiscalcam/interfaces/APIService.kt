package br.net.ari.lprfiscalcam.interfaces

import br.net.ari.lprfiscalcam.models.Camera
import br.net.ari.lprfiscalcam.models.Cliente
import br.net.ari.lprfiscalcam.models.Fiscalizacao
import br.net.ari.lprfiscalcam.models.Veiculo
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface APIService {
    @GET("Cliente/GetByLoginAndSenha/{login}/{senha}")
    fun getClienteByLoginAndSenha(
        @Path("login") login: String?,
        @Path("senha") senha: String?
    ): Call<Cliente?>

    @GET("Fiscalizacao/GetByCliente")
    fun getFiscalizacoes(): Call<List<Fiscalizacao>?>

    @GET("FiscalizacaoVeiculo/GetFromCameraANPR/{placa}/{fiscalizacaoId}/{dispositivo}/{cameraId}")
    fun getVeiculoV2(
        @Path("placa") placa: String?,
        @Path("fiscalizacaoId") fiscalizacaoId: Long,
        @Path("dispositivo") dispositivo: String?,
        @Path("cameraId") cameraId: Long
        ): Call<Veiculo?>

    @GET("FiscalizacaoVeiculo/GetFromCameraANPR/{placa}/{fiscalizacaoId}")
    fun getVeiculo(
        @Path("placa") placa: String?,
        @Path("fiscalizacaoId") fiscalizacaoId: Long
    ): Call<Veiculo?>

    @GET("Camera/GetCameraByChave/{chave}")
    fun getCameraByChave(
        @Path("chave") login: String?
    ): Call<Camera?>

    @POST("FiscalizacaoVeiculo/SaveFromVaxtorANPR")
    fun postVeiculo(@Body veiculo: Veiculo?): Call<String?>

    @PATCH("Camera/SetC2VByChave")
    fun patchC2VByChave(@Body camera: Camera?): Call<Camera?>
}