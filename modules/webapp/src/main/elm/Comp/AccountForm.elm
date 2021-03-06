module Comp.AccountForm exposing
    ( FormAction(..)
    , Model
    , Msg
    , init
    , initModify
    , initNew
    , update
    , view
    )

import Api.Model.AccountCreate exposing (AccountCreate)
import Api.Model.AccountDetail exposing (AccountDetail)
import Api.Model.AccountModify exposing (AccountModify)
import Comp.FixedDropdown
import Comp.PasswordInput
import Data.AccountState exposing (AccountState)
import Html exposing (..)
import Html.Attributes exposing (..)
import Html.Events exposing (onCheck, onClick, onInput)
import Messages.AccountForm exposing (Texts)
import Util.Maybe


type alias Model =
    { existing : Maybe AccountDetail
    , loginField : String
    , emailField : Maybe String
    , passwordModel : Comp.PasswordInput.Model
    , passwordField : Maybe String
    , stateModel : Comp.FixedDropdown.Model AccountState
    , stateField : Comp.FixedDropdown.Item AccountState
    , adminField : Bool
    }


init : Maybe AccountDetail -> Model
init ma =
    Maybe.map initModify ma
        |> Maybe.withDefault initNew


mkStateItem : AccountState -> Comp.FixedDropdown.Item AccountState
mkStateItem state =
    Comp.FixedDropdown.Item
        state
        (Data.AccountState.toString state)
        Nothing


initNew : Model
initNew =
    { existing = Nothing
    , loginField = ""
    , emailField = Nothing
    , passwordModel = Comp.PasswordInput.init
    , passwordField = Nothing
    , stateModel = Comp.FixedDropdown.initMap Data.AccountState.toString Data.AccountState.all
    , stateField = mkStateItem Data.AccountState.Active
    , adminField = False
    }


initModify : AccountDetail -> Model
initModify acc =
    { initNew
        | existing = Just acc
        , loginField = acc.login
        , emailField = acc.email
        , stateField =
            Data.AccountState.fromStringOrActive acc.state
                |> mkStateItem
        , adminField = acc.admin
    }


type Msg
    = SetLogin String
    | SetEmail String
    | PasswordMsg Comp.PasswordInput.Msg
    | StateMsg (Comp.FixedDropdown.Msg AccountState)
    | ToggleAdmin
    | Cancel
    | Submit


type FormAction
    = FormModified String AccountModify
    | FormCreated AccountCreate
    | FormCancelled
    | FormNone


isCreate : Model -> Bool
isCreate model =
    model.existing == Nothing


isModify : Model -> Bool
isModify model =
    not (isCreate model)


isIntern : Model -> Bool
isIntern model =
    Maybe.map .source model.existing
        |> Maybe.map ((==) "intern")
        |> Maybe.withDefault False


formInvalid : Model -> Bool
formInvalid model =
    String.isEmpty model.loginField
        || (isCreate model && model.passwordField == Nothing)


update : Msg -> Model -> ( Model, FormAction )
update msg model =
    case msg of
        SetLogin str ->
            ( { model | loginField = str }, FormNone )

        SetEmail str ->
            ( { model
                | emailField = Util.Maybe.fromString str
              }
            , FormNone
            )

        PasswordMsg lmsg ->
            let
                ( m, pw ) =
                    Comp.PasswordInput.update lmsg model.passwordModel
            in
            ( { model
                | passwordModel = m
                , passwordField = pw
              }
            , FormNone
            )

        StateMsg lmsg ->
            let
                ( m, sel ) =
                    Comp.FixedDropdown.update lmsg model.stateModel
            in
            ( { model
                | stateModel = m
                , stateField =
                    Maybe.map mkStateItem sel
                        |> Maybe.withDefault model.stateField
              }
            , FormNone
            )

        ToggleAdmin ->
            ( { model | adminField = not model.adminField }
            , FormNone
            )

        Cancel ->
            ( model, FormCancelled )

        Submit ->
            if formInvalid model then
                ( model, FormNone )

            else
                case Maybe.map .id model.existing of
                    Just id ->
                        ( model
                        , FormModified id
                            { state = Data.AccountState.toString model.stateField.id
                            , admin = model.adminField
                            , email = model.emailField
                            , password = model.passwordField
                            }
                        )

                    Nothing ->
                        ( model
                        , FormCreated
                            { login = model.loginField
                            , state = Data.AccountState.toString model.stateField.id
                            , admin = model.adminField
                            , email = model.emailField
                            , password = Maybe.withDefault "" model.passwordField
                            }
                        )


view : Texts -> Model -> Html Msg
view texts model =
    div [ class "ui segments" ]
        [ Html.form [ class "ui form segment" ]
            [ div
                [ classList
                    [ ( "disabled field", True )
                    , ( "invisible", isCreate model )
                    ]
                ]
                [ label [] [ text texts.id ]
                , input
                    [ type_ "text"
                    , Maybe.map .id model.existing
                        |> Maybe.withDefault "-"
                        |> value
                    ]
                    []
                ]
            , div
                [ classList
                    [ ( "required field", True )
                    , ( "disabled", isModify model )
                    , ( "error", String.isEmpty model.loginField )
                    ]
                ]
                [ label [] [ text texts.login ]
                , input
                    [ type_ "text"
                    , value model.loginField
                    , onInput SetLogin
                    ]
                    []
                ]
            , div [ class "required field" ]
                [ label [] [ text texts.state ]
                , Html.map StateMsg
                    (Comp.FixedDropdown.view
                        (Just model.stateField)
                        texts.dropdown
                        model.stateModel
                    )
                ]
            , div [ class "inline required field" ]
                [ div [ class "ui checkbox" ]
                    [ input
                        [ type_ "checkbox"
                        , onCheck (\_ -> ToggleAdmin)
                        , checked model.adminField
                        ]
                        []
                    , label [] [ text texts.admin ]
                    ]
                ]
            , div [ class "field" ]
                [ label [] [ text "E-Mail" ]
                , input
                    [ type_ "text"
                    , Maybe.withDefault "" model.emailField |> value
                    , onInput SetEmail
                    ]
                    []
                ]
            , div
                [ classList
                    [ ( "field", True )
                    , ( "error", isCreate model && model.passwordField == Nothing )
                    , ( "required", isCreate model )
                    , ( "disabled", not (isCreate model || isIntern model) )
                    ]
                ]
                [ label [] [ text texts.password ]
                , Html.map PasswordMsg
                    (Comp.PasswordInput.view model.passwordField
                        model.passwordModel
                    )
                ]
            ]
        , div [ class "ui secondary segment" ]
            [ button
                [ type_ "button"
                , class "ui primary button"
                , onClick Submit
                ]
                [ text texts.submit
                ]
            , button
                [ class "ui button"
                , type_ "button"
                , onClick Cancel
                ]
                [ text texts.back
                ]
            ]
        ]
