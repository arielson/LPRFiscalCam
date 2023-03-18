package br.net.ari.lprfiscalcam.interfaces

import br.net.ari.lprfiscalcam.models.Cliente
import br.net.ari.lprfiscalcam.models.Fiscalizacao
import br.net.ari.lprfiscalcam.models.Veiculo
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface APIService {
    @GET("Cliente/GetByLoginAndSenha/{login}/{senha}")
    fun GetClienteByLoginAndSenha(
        @Path("login") login: String?,
        @Path("senha") senha: String?
    ): Call<Cliente?>

    @GET("Fiscalizacao/GetByCliente")
    fun GetFiscalizacoes(): Call<List<Fiscalizacao?>?>

    @GET("FiscalizacaoVeiculo/GetFromCameraANPR/{placa}/{fiscalizacaoId}")
    fun GetVeiculo(
        @Path("placa") placa: String?,
        @Path("fiscalizacaoId") fiscalizacaoId: Long
    ): Call<Veiculo?>

    @POST("FiscalizacaoVeiculo/SaveFromVaxtorANPR")
    fun PostVeiculo(@Body veiculo: Veiculo?): Call<String?>
}