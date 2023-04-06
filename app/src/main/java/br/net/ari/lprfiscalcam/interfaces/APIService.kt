package br.net.ari.lprfiscalcam.interfaces

import br.net.ari.lprfiscalcam.models.*
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

    @POST("FiscalizacaoVeiculo/SetFromCameraANPR")
    fun setVeiculo(@Body veiculo: Veiculo?): Call<Veiculo?>

    @POST("FiscalizacaoVeiculo/SaveFromVaxtorANPR")
    fun postVeiculo(@Body veiculo: Veiculo?): Call<String?>

    @GET("Camera/GetCameraByChaveVaxtor/{chave}")
    fun getCameraByChaveVaxtor(
        @Path("chave") chave: String?
    ): Call<Camera?>

    @GET("Camera/GetCameraByChave/{chave}")
    fun getCameraByChave(
        @Path("chave") chave: String?
    ): Call<Camera?>

    @PATCH("Camera/SetC2VByChave")
    fun patchC2VByChave(@Body camera: Camera?): Call<Camera?>

    @POST("CameraLog/SetLog")
    fun setLog(@Body camera: CameraLog?): Call<Void?>
}