package br.net.ari.lprfiscalcam.interfaces

import br.net.ari.lprfiscalcam.models.*
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

interface APIService {
    @GET("v1/Cliente/GetByLoginAndSenha/{login}/{senha}")
    fun getClienteByLoginAndSenha(
        @Path("login") login: String?,
        @Path("senha") senha: String?
    ): Call<Cliente?>

    @GET("v1/Fiscalizacao/GetByCliente")
    fun getFiscalizacoes(): Call<List<Fiscalizacao>?>

    @POST("v1/FiscalizacaoVeiculo/SetFromCameraANPR")
    fun setVeiculo(@Body veiculo: Veiculo?): Call<Veiculo?>

    @POST("v1/FiscalizacaoVeiculo/SaveFromVaxtorANPR")
    fun postVeiculo(@Body veiculo: Veiculo?): Call<String?>

//    @GET("v1/Camera/GetCameraByChaveVaxtor/{chave}")
//    fun getCameraByChaveVaxtor(
//        @Path("chave") chave: String?
//    ): Call<Camera?>

//    @GET("v1/Camera/GetCameraByChave/{chave}")
//    fun getCameraByChave(
//        @Path("chave") chave: String?
//    ): Call<Camera?>

    @GET("v2/Camera/GetCameraByChave/{chave}/{uuid}")
    fun getCameraByChave(
        @Path("chave") chave: String?,
        @Path("uuid") uuid: String?
    ): Call<Camera?>

    @GET("v2/Camera/CleanCameraByChave/{chave}/{uuid}")
    fun cleanCameraByChave(
        @Path("chave") chave: String?,
        @Path("uuid") uuid: String?
    ): Call<Camera?>

    @POST("v1/FiscalizacaoVeiculo/GetPlaca")
    fun getPlaca(@Body veiculo: Veiculo?): Call<Veiculo?>

    @PATCH("v1/Camera/AtualizaBateriaTemperaturaByChave")
    fun setCamera(@Body camera: Camera?): Call<Camera?>
}